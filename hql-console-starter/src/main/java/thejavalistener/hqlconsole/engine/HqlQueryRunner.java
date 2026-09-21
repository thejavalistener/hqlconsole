package thejavalistener.hqlconsole.engine;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;

/**
 * Ejecuta las sentencias que llegan de la consola contra el {@code EntityManagerFactory} vivo.
 *
 * <p>Hay dos familias de sentencias:</p>
 * <ul>
 *   <li><b>HQL</b> ({@code select}, {@code from}, y el bulk {@code delete} de HQL): se le pasan a
 *       Hibernate tal cual, salvo dos cosas que HQL no entiende y la consola sí: el
 *       {@code SELECT * FROM X} (se traduce a {@code FROM X}) y un {@code LIMIT n} al final, que se
 *       aplica con {@code setMaxResults}.</li>
 *   <li><b>Propias de la consola</b> ({@code INSERT ... VALUES}, {@code UPDATE ... SET},
 *       {@code DESC}): no son HQL, las interpreta
 *       {@link StatementParser} y las ejecuta esta clase.</li>
 * </ul>
 *
 * <p>Decisiones que aplican a todo:</p>
 * <ul>
 *   <li><b>Un EntityManager nuevo por sentencia</b>, tomado del factory de la aplicación. No
 *       participa de las transacciones de Spring a propósito: una consola de desarrollo quiere
 *       cada sentencia aislada, no enganchada a la transacción de un request ajeno.</li>
 *   <li><b>Transacción resource-local</b> ({@code em.getTransaction()}) para todo: las lecturas
 *       abren y hacen rollback, las escrituras hacen commit.</li>
 *   <li><b>Las filas se materializan dentro de la transacción</b>, con la sesión abierta, así las
 *       asociaciones perezosas que la sentencia haya traído se pueden resolver.</li>
 * </ul>
 */
public class HqlQueryRunner
{
	private static final Logger log=LoggerFactory.getLogger(HqlQueryRunner.class);

	private final ObjectProvider<EntityManagerFactory> entityManagerFactory;
	private final EntityDescriber describer;
	private final int maxRows;

	public HqlQueryRunner(ObjectProvider<EntityManagerFactory> entityManagerFactory,EntityDescriber describer,int maxRows)
	{
		this.entityManagerFactory=entityManagerFactory;
		this.describer=describer;
		this.maxRows=maxRows>0?maxRows:0;
	}

	public HqlResult execute(String hql)
	{
		return execute(hql,false);
	}

	/**
	 * Igual que {@link #execute(String)}, pero con {@code dryRun} el trabajo se hace y después se
	 * tira atrás: la transacción termina en {@code rollback} en vez de {@code commit}.
	 *
	 * <p>Es lo que permite contar cuántas filas va a tocar un {@code UPDATE} o un {@code DELETE} sin
	 * tocar nada, para que la página lo muestre antes de confirmar. El trabajo es exactamente el
	 * mismo, así que el número es el real y no una estimación.</p>
	 *
	 * <p>Lo que hay que saber: la sentencia se ejecuta dos veces (una descartada y una de verdad), y
	 * los efectos por fuera de la transacción —un listener que manda un mail, un trigger que escribe
	 * en otra conexión— pasan dos veces.</p>
	 */
	public HqlResult execute(String hql,boolean dryRun)
	{
		EntityManagerFactory emf=_entityManagerFactory();
		String statement=hql.trim();
		String first=Text.firstWord(statement);
		long t0=System.nanoTime();

		if( "desc".equalsIgnoreCase(first)||"describe".equalsIgnoreCase(first) )
		{
			return _runDesc(emf,statement,t0);
		}
		if( "insert".equalsIgnoreCase(first)||"update".equalsIgnoreCase(first) )
		{
			return _runConsoleWriteOrHql(emf,statement,t0,dryRun);
		}
		if( "delete".equalsIgnoreCase(first) )
		{
			return _runBulkWrite(emf,statement,t0,dryRun);
		}
		return _runQuery(emf,statement,t0);
	}

	// ==================== sentencias propias de la consola ====================

	/**
	 * Intenta leer la sentencia como {@code INSERT ... VALUES} o {@code UPDATE ... SET}. Si no
	 * entra en esa gramática, cae al bulk de HQL de siempre: un {@code insert into ... select} o un
	 * update con join siguen funcionando.
	 */
	private HqlResult _runConsoleWriteOrHql(EntityManagerFactory emf,String statement,long t0,boolean dryRun)
	{
		Statement parsed=null;
		IllegalArgumentException parseFailure=null;
		try
		{
			parsed=StatementParser.parse(statement);
		}
		catch(IllegalArgumentException e)
		{
			parseFailure=e;
		}

		if( parsed!=null )
		{
			EntityManager em=emf.createEntityManager();
			try
			{
				return _runConsoleWrite(em,emf,parsed,t0,dryRun);
			}
			finally
			{
				em.close();
			}
		}

		try
		{
			return _runBulkWrite(emf,statement,t0,dryRun);
		}
		catch(RuntimeException hqlFailure)
		{
			if( parseFailure!=null )
			{
				throw new IllegalArgumentException("La sentencia no entra en la gramática de la consola ("
						+parseFailure.getMessage()+") y como HQL tampoco anduvo ("+hqlFailure.getMessage()+").",hqlFailure);
			}
			throw hqlFailure;
		}
	}

	private HqlResult _runConsoleWrite(EntityManager em,EntityManagerFactory emf,Statement parsed,long t0,boolean dryRun)
	{
		EntityType<?> entityType=_entityType(emf,parsed.entity());
		EntityTransaction tx=_begin(em);
		try
		{
			HqlResult result=parsed.kind()==Statement.Kind.INSERT
					?_insert(em,emf,entityType,parsed,t0)
					:_update(em,entityType,parsed,t0);
			_cerrar(tx,dryRun);
			return result;
		}
		catch(RuntimeException e)
		{
			_rollbackQuietly(tx);
			throw e;
		}
	}

	/**
	 * Termina la transacción: commit, o rollback si es un dry-run.
	 *
	 * <p>Es lo único que cambia entre ejecutar de verdad y contar sin tocar nada, y por eso el
	 * dry-run usa el mismo camino de código que la ejecución real.</p>
	 */
	private void _cerrar(EntityTransaction tx,boolean dryRun)
	{
		if( dryRun )
		{
			tx.rollback();
		}
		else
		{
			tx.commit();
		}
	}

	/** {@code INSERT INTO Libro li VALUES li.titulo='...', li.fechaAlta=NOW} */
	private HqlResult _insert(EntityManager em,EntityManagerFactory emf,EntityType<?> entityType,Statement parsed,long t0)
	{
		AttributeBinder binder=new AttributeBinder(em);
		Object entity=_instantiate(entityType);

		// Los campos que no aparecen en la sentencia quedan como están: NULL si la columna lo
		// acepta, o el error de la base si no lo acepta. No inventamos valores.
		for(Statement.Assignment assignment:parsed.assignments())
		{
			AttributeBinder.Target target=binder.resolve(entityType,parsed.alias(),assignment.path());
			binder.apply(entity,target,binder.value(target,assignment.literal()));
		}

		em.persist(entity);
		em.flush(); // con IDENTITY el insert sale acá; con SEQUENCE deja el id ya asignado

		Object id=emf.getPersistenceUnitUtil().getIdentifier(entity);
		return HqlResult.dml("INSERT",1,_millis(t0),"Insertado "+entityType.getName()+(id==null?"":"#"+id));
	}

	/**
	 * {@code UPDATE Libro li SET li.titulo='...' WHERE li.id=132}
	 *
	 * <p>El WHERE viaja textual a Hibernate dentro de un {@code select}, así que no hace falta
	 * parsear expresiones: vale cualquier cosa que Hibernate entienda. Después se modifican sólo
	 * los campos del SET, sobre entidades manejadas, y el commit dispara los UPDATE (con
	 * {@code @PreUpdate} y {@code @Version} incluidos).</p>
	 */
	private HqlResult _update(EntityManager em,EntityType<?> entityType,Statement parsed,long t0)
	{
		String alias=parsed.alias()==null?"e":parsed.alias();

		StringBuilder hql=new StringBuilder("select ").append(alias)
				.append(" from ").append(parsed.entity()).append(' ').append(alias);
		if( parsed.where()!=null )
		{
			hql.append(" where ").append(parsed.where());
		}

		Query query=em.createQuery(hql.toString());
		_limit(query);
		List<?> found=query.getResultList();

		boolean truncated=maxRows>0&&found.size()>maxRows;
		List<?> targets=truncated?found.subList(0,maxRows):found;

		AttributeBinder binder=new AttributeBinder(em);
		for(Object entity:targets)
		{
			for(Statement.Assignment assignment:parsed.assignments())
			{
				AttributeBinder.Target target=binder.resolve(entityType,parsed.alias(),assignment.path());
				binder.apply(entity,target,binder.value(target,assignment.literal()));
			}
		}

		em.flush();
		String message=targets.size()+" fila(s) actualizada(s)"
				+(truncated?" — se alcanzó el tope de "+maxRows+" filas y NO se tocó el resto":"");
		return HqlResult.dml("UPDATE",targets.size(),truncated,_millis(t0),message);
	}

	/** {@code DESC} o {@code DESC <Entidad>} */
	private HqlResult _runDesc(EntityManagerFactory emf,String statement,long t0)
	{
		Statement parsed=StatementParser.parse(statement);
		if( parsed==null||parsed.kind()!=Statement.Kind.DESC )
		{
			throw new IllegalArgumentException("DESC espera una entidad o nada: DESC [Entidad]");
		}
		if( parsed.entity()==null )
		{
			return describer.describeEntities(emf.getMetamodel(),t0);
		}
		return describer.describe(emf.getMetamodel(),_entityType(emf,parsed.entity()),t0);
	}

	// ==================== HQL ====================

	/** Bulk de HQL: un solo UPDATE/DELETE/INSERT ... SELECT, sin pasar por el persistence context. */
	private HqlResult _runBulkWrite(EntityManagerFactory emf,String statement,long t0,boolean dryRun)
	{
		EntityManager em=emf.createEntityManager();
		try
		{
			EntityTransaction tx=_begin(em);
			try
			{
				int affected=em.createQuery(statement).executeUpdate();
				_cerrar(tx,dryRun);
				return HqlResult.dml(Text.firstWord(statement).toUpperCase(Locale.ROOT),affected,_millis(t0),
						affected+" fila(s) afectada(s)");
			}
			catch(RuntimeException e)
			{
				_rollbackQuietly(tx);
				throw e;
			}
		}
		finally
		{
			em.close();
		}
	}

	private HqlResult _runQuery(EntityManagerFactory emf,String statement,long t0)
	{
		// El LIMIT y el "*" son gramática de la consola, no de HQL: se sacan antes de mandar la
		// sentencia a Hibernate, y lo que queda es HQL de verdad (o "from <Entidad> ...").
		Consulta consulta=_parsearLimite(statement);
		String texto=_sinSelectEstrella(consulta.texto());

		// Cuántas filas se piden: gana el menor entre el LIMIT explícito y el tope global. Con un
		// LIMIT que entra en el tope no hace falta pedir una fila de más: ese recorte es lo que el
		// usuario pidió, no una truncación; el "de más" sólo sirve para avisar que hay más filas.
		int pedido=consulta.limite()==null?0:consulta.limite();
		int tope;
		boolean pedirUnaMas;
		if( pedido>0&&(maxRows<=0||maxRows>=pedido) )
		{
			tope=pedido;
			pedirUnaMas=false;
		}
		else
		{
			tope=maxRows;
			pedirUnaMas=maxRows>0;
		}

		EntityManager em=emf.createEntityManager();
		try
		{
			// Se resuelve antes de ejecutar la consulta para que un nombre mal escrito dé el error
			// con la sugerencia, y no el de Hibernate.
			EntityType<?> flatEntity=_implicitEntitySelect(emf,texto);

			EntityTransaction tx=_begin(em);
			try
			{
				List<?> raw;
				List<String> headers;

				try
				{
					// Camino preferido: Tuple.class da los alias del select cuando la consulta los
					// declara ("SELECT e.id AS id ..." -> header id).
					//
					// Ojo: según la especificación Jakarta Persistence, pasarle algo distinto de un
					// tipo escalar único a createQuery(String, Class) NO es portable ("Applications
					// that specify other result types (e.g., Tuple.class) will not be portable").
					// Hibernate lo acepta y es lo que usamos acá; el catch de abajo es el camino
					// portable, así que un proveedor que lo rechace degrada en vez de romper.
					TypedQuery<Tuple> typed=em.createQuery(texto,Tuple.class);
					_limite(typed,tope,pedirUnaMas);
					List<Tuple> tuples=typed.getResultList();
					headers=_headersOf(tuples,texto);
					raw=tuples;
				}
				catch(RuntimeException providerRejectsTuple)
				{
					// Típicamente el select de una entidad entera. Camino JPA plano y portable:
					// sin metadata de columnas, con headers derivados por posición.
					log.debug("La consulta no se pudo leer como Tuple; se usa el camino JPA plano sin alias: {}",
							providerRejectsTuple.getMessage());
					Query plain=em.createQuery(texto);
					_limite(plain,tope,pedirUnaMas);
					raw=plain.getResultList();
					headers=List.of();
				}

				boolean truncated=tope>0&&raw.size()>tope;
				List<?> visible=truncated?raw.subList(0,tope):raw;

				// "from <Entidad>" sin SELECT explícito: en vez de una sola columna con "Libro#1",
				// se muestran todas las columnas planas de la entidad, y las relaciones como su FK.
				List<List<Object>> rows=flatEntity==null?null:_flatRows(visible,flatEntity,emf);

				if( rows==null )
				{
					flatEntity=null;
					rows=_toRows(visible,emf);
					if( headers.isEmpty() )
					{
						List<String> synthesized=_synthesizeHeaders(texto,rows.isEmpty()?-1:rows.get(0).size());
						headers=synthesized==null?_defaultHeaders(rows):synthesized;
					}
				}
				else
				{
					headers=_flatHeaders(flatEntity);
				}
				log.debug("Sentencia leída con {} fila(s) y headers {}",rows.size(),headers);

				tx.rollback(); // es una lectura: no dejamos la transacción abierta
				return HqlResult.query(headers,rows,truncated,_millis(t0),_mensajeLimite(consulta,truncated));
			}
			catch(RuntimeException e)
			{
				_rollbackQuietly(tx);
				throw e;
			}
		}
		finally
		{
			em.close();
		}
	}

	/** Lo que se muestra al pie cuando la sentencia llevaba un {@code LIMIT}. */
	private String _mensajeLimite(Consulta consulta,boolean truncated)
	{
		if( consulta.limite()==null )
		{
			return null;
		}
		return truncated
				?"LIMIT "+consulta.limite()+" recortado antes por el tope de "+maxRows+" filas"
				:"LIMIT "+consulta.limite();
	}

	// ==================== gramática de la consola dentro de una consulta ====================

	/** Una consulta ya sin la cláusula LIMIT, con el valor que pedía (o {@code null}). */
	private record Consulta(String texto,Integer limite) {}

	/**
	 * Saca el {@code LIMIT n} del final de la consulta, si está.
	 *
	 * <p>Va al final y nada más que al final, que es lo que pidió el diseño: la sentencia puede ser
	 * larga, tener WHERE y ORDER BY, y terminar en {@code LIMIT n}. El valor se aplica con
	 * {@code setMaxResults} (el "maxRows" de JDBC), no con {@code fetchSize}: {@code fetchSize} sólo
	 * insinúa de a cuántas filas traer por viaje y no cambia cuántas devuelve la consulta.</p>
	 *
	 * <p>El escaneo es de nivel 0, así que un {@code limit} dentro de un literal o de una subconsulta
	 * no se toca: {@code WHERE e.nombre LIKE '%limit 5%'} y {@code (select ... limit 1)} siguen
	 * viajando tal cual a Hibernate.</p>
	 */
	private Consulta _parsearLimite(String statement)
	{
		List<Integer> posiciones=new ArrayList<>();
		for(int i=0;i<statement.length();)
		{
			int encontrado=Text.indexOfKeyword(statement,"limit",i);
			if( encontrado<0 )
			{
				break;
			}
			posiciones.add(encontrado);
			i=encontrado+5;
		}
		if( posiciones.isEmpty() )
		{
			return new Consulta(statement,null);
		}
		if( posiciones.size()>1 )
		{
			throw new IllegalArgumentException("Hay más de un LIMIT en la sentencia: el LIMIT va una sola vez, al final (LIMIT n)");
		}

		int at=posiciones.get(0);
		String cola=statement.substring(at+5).trim();
		if( !cola.matches("\\d+") )
		{
			throw new IllegalArgumentException("LIMIT espera un número entero al final de la sentencia: ... LIMIT n"
					+(cola.isEmpty()?"":" (encontré '"+cola+"')"));
		}
		int filas;
		try
		{
			filas=Integer.parseInt(cola);
		}
		catch(NumberFormatException demasiadoGrande)
		{
			throw new IllegalArgumentException("El LIMIT "+cola+" es demasiado grande");
		}
		if( filas<1 )
		{
			throw new IllegalArgumentException("El LIMIT tiene que ser mayor que cero (pediste "+filas+")");
		}
		return new Consulta(statement.substring(0,at).trim(),filas);
	}

	/**
	 * {@code SELECT * FROM X ...} pasado a {@code FROM X ...}, que es la forma que la consola ya
	 * aplana a columnas. Hibernate no acepta el {@code *} en HQL (tira {@code SyntaxException}), y
	 * escribir {@code select *} es lo natural para cualquiera que venga de SQL, así que se traduce en
	 * vez de rechazarlo. Si la sentencia no es exactamente esa forma se devuelve sin tocar.
	 */
	private String _sinSelectEstrella(String statement)
	{
		if( !Text.startsWithWord(statement,"select") )
		{
			return statement;
		}
		int i=6;
		while( i<statement.length()&&Character.isWhitespace(statement.charAt(i)) )
		{
			i++;
		}
		if( i>=statement.length()||statement.charAt(i)!='*' )
		{
			return statement;
		}
		i++;
		while( i<statement.length()&&Character.isWhitespace(statement.charAt(i)) )
		{
			i++;
		}
		// Sin el FROM detrás no es un "select * from ...": se deja como está para que Hibernate diga
		// lo que corresponda.
		return Text.wordAt(statement,i,"from")?statement.substring(i):statement;
	}

	// ==================== lote de sentencias ====================

	/**
	 * Ejecuta varias sentencias de la consola en <b>una sola transacción</b>: o entran todas o no
	 * entra ninguna. Es para dar de alta datos de prueba de un saque.
	 *
	 * <p>Sólo INSERT. Cada sentencia entra por el mismo camino que una suelta, así que los tres
	 * formatos de INSERT funcionan igual adentro del lote y se conservan las conversiones
	 * ({@code NOW}, enums, relaciones por id). Lo que no entra en la gramática de la consola se
	 * rechaza con la posición, en vez de mandarlo a Hibernate: un bulk de HQL se maneja su propia
	 * transacción y rompería la promesa de "todo o nada".</p>
	 */
	public HqlResult executeBatch(List<String> statements)
	{
		EntityManagerFactory emf=_entityManagerFactory();
		long t0=System.nanoTime();

		List<Statement> parsed=_parseBatch(statements);

		EntityManager em=emf.createEntityManager();
		try
		{
			EntityTransaction tx=_begin(em);
			try
			{
				int filas=0;
				for(int i=0;i<parsed.size();i++)
				{
					Statement statement=parsed.get(i);
					try
					{
						EntityType<?> entityType=_entityType(emf,statement.entity());
						filas+=_insert(em,emf,entityType,statement,t0).affectedRows();
					}
					catch(RuntimeException e)
					{
						// En un lote de 50 sentencias, saber cuál falló es la mitad del diagnóstico.
						throw new IllegalArgumentException("La sentencia "+(i+1)+" de "+parsed.size()
								+" falló, así que no se insertó ninguna: "+e.getMessage(),e);
					}
				}
				tx.commit();
				return HqlResult.batch(filas,parsed.size(),_millis(t0),
						filas+" fila(s) insertada(s) en "+parsed.size()+" sentencia(s)");
			}
			catch(RuntimeException e)
			{
				_rollbackQuietly(tx);
				throw e;
			}
		}
		finally
		{
			em.close();
		}
	}

	/** Valida el lote entero antes de tocar la base: si una sentencia no sirve, no se ejecuta nada. */
	private List<Statement> _parseBatch(List<String> statements)
	{
		List<Statement> parsed=new ArrayList<>(statements.size());
		for(int i=0;i<statements.size();i++)
		{
			String texto=statements.get(i);
			Statement statement;
			try
			{
				statement=StatementParser.parse(texto);
			}
			catch(IllegalArgumentException e)
			{
				throw new IllegalArgumentException("La sentencia "+(i+1)+" de "+statements.size()
						+" no se entiende: "+e.getMessage(),e);
			}
			if( statement==null||statement.kind()!=Statement.Kind.INSERT )
			{
				throw new IllegalArgumentException("La sentencia "+(i+1)+" de "+statements.size()
						+" no es un INSERT: un lote sólo sirve para dar de alta datos (empieza con '"
						+Text.firstWord(texto)+"')");
			}
			parsed.add(statement);
		}
		return parsed;
	}

	// ==================== "from Entidad" aplanado ====================

	/**
	 * Reconoce el caso {@code from <Entidad> ...} —sin SELECT explícito— y devuelve la entidad raíz,
	 * que es la que se aplana.
	 *
	 * <p>No hace falta validar la forma de la cláusula: {@link #_flatRows} devuelve {@code null} si
	 * las filas no son exactamente esa entidad (por ejemplo con un join explícito o una lista de
	 * raíces separadas por comas), y ahí se sigue por el camino normal. Lo único que se exige acá es
	 * que la sentencia empiece con {@code from}, porque con un SELECT explícito se devuelve
	 * exactamente lo que se pidió.</p>
	 */
	private EntityType<?> _implicitEntitySelect(EntityManagerFactory emf,String statement)
	{
		if( !Text.startsWithWord(statement,"from") )
		{
			return null;
		}
		String entity=Text.identifierAt(statement,4);
		return entity==null?null:_entityType(emf,entity);
	}

	private List<String> _flatHeaders(EntityType<?> entityType)
	{
		// Los títulos son los atributos de la clase, no las columnas físicas de la tabla: son los
		// nombres que uno escribió en la entidad y los que puede volver a escribir en un HQL. El
		// nombre físico sigue estando en DESC, que es donde tiene sentido verlo.
		List<String> headers=new ArrayList<>();
		for(Attribute<?,?> attribute:Mapping.columnsInDeclarationOrder(entityType))
		{
			headers.add(attribute.getName());
		}
		return headers;
	}

	/** Devuelve null si las filas no tienen la forma esperada, para que siga el camino normal. */
	private List<List<Object>> _flatRows(List<?> raw,EntityType<?> entityType,EntityManagerFactory emf)
	{
		PersistenceUnitUtil util=emf.getPersistenceUnitUtil();
		List<EntityType<?>> entityTypes=new ArrayList<>(emf.getMetamodel().getEntities());
		List<Attribute<?,?>> columns=Mapping.columnsInDeclarationOrder(entityType);
		Class<?> javaType=entityType.getJavaType();

		List<List<Object>> rows=new ArrayList<>(raw.size());
		for(Object row:raw)
		{
			Object[] values=_values(row);
			if( values.length!=1||!javaType.isInstance(values[0]) )
			{
				return null;
			}

			Object entity=values[0];
			List<Object> cells=new ArrayList<>(columns.size());
			for(Attribute<?,?> attribute:columns)
			{
				Object value=Mapping.read(entity,attribute);
				// Una relación se muestra como el id de la FK, sin inicializar el proxy.
				cells.add(_cell(Mapping.isToOne(attribute)?_identifier(value,util):value,util,entityTypes));
			}
			rows.add(cells);
		}
		return rows;
	}

	private Object[] _values(Object row)
	{
		if( row instanceof Tuple tuple )
		{
			return tuple.toArray();
		}
		if( row instanceof Object[] array )
		{
			return array;
		}
		return new Object[]{row};
	}

	/** El id sin inicializar un proxy: {@code getIdentifier} no le manda ningún mensaje al proxy. */
	private Object _identifier(Object value,PersistenceUnitUtil util)
	{
		if( value==null )
		{
			return null;
		}
		try
		{
			return util.getIdentifier(value);
		}
		catch(RuntimeException notAnEntity)
		{
			return value;
		}
	}

	// ==================== headers ====================

	private List<String> _headersOf(List<Tuple> tuples,String statement)
	{
		if( tuples.isEmpty() )
		{
			// Sin filas no hay metadata de columnas: al menos intentamos leer el select.
			List<String> synthesized=_synthesizeHeaders(statement,-1);
			return synthesized==null?List.of():synthesized;
		}

		List<TupleElement<?>> elements=tuples.get(0).getElements();
		List<String> aliases=new ArrayList<>(elements.size());
		boolean anyMissing=false;
		for(TupleElement<?> element:elements)
		{
			String alias=element.getAlias();
			aliases.add(alias);
			anyMissing|=alias==null||alias.isBlank();
		}
		if( !anyMissing )
		{
			return aliases;
		}

		// JPQL sólo garantiza el alias cuando la consulta lo declara con AS: sin AS,
		// TupleElement.getAlias() puede venir null. Los deducimos del propio select.
		List<String> synthesized=_synthesizeHeaders(statement,aliases.size());

		List<String> headers=new ArrayList<>(aliases.size());
		for(int i=0;i<aliases.size();i++)
		{
			String alias=aliases.get(i);
			if( alias!=null&&!alias.isBlank() )
			{
				headers.add(alias);
			}
			else if( synthesized!=null&&i<synthesized.size() )
			{
				headers.add(synthesized.get(i));
			}
			else
			{
				headers.add("col"+(i+1));
			}
		}
		return headers;
	}

	/**
	 * Deduce los nombres de columna leyendo la lista del {@code select} cuando el proveedor no
	 * da alias. Es sólo cosmético y conservador: si el parseo no coincide con la cantidad de
	 * columnas reales devuelve {@code null} y el que llama usa {@code col1..colN}.
	 *
	 * @param expectedCount columnas reales, o -1 si no se conocen (resultado vacío).
	 */
	private List<String> _synthesizeHeaders(String statement,int expectedCount)
	{
		String text=statement.trim();

		if( !Text.startsWithWord(text,"select") )
		{
			// "from Empleado e": una sola columna, el nombre de la entidad sirve de header.
			if( expectedCount==1&&Text.startsWithWord(text,"from") )
			{
				String entity=Text.identifierAt(text,5);
				return entity==null?null:List.of(entity);
			}
			return null;
		}

		String rest=text.substring(6).stripLeading();
		if( Text.startsWithWord(rest,"distinct") )
		{
			rest=rest.substring(8).stripLeading();
		}

		int from=Text.indexOfKeyword(rest,"from");
		if( from<0 )
		{
			return null;
		}

		List<String> items=Text.splitTopLevel(rest.substring(0,from));
		if( expectedCount>=0&&items.size()!=expectedCount )
		{
			return null;
		}

		List<String> headers=new ArrayList<>(items.size());
		for(String item:items)
		{
			headers.add(_labelFor(item));
		}
		return headers;
	}

	/** Nombre a mostrar para un ítem del select. */
	private String _labelFor(String item)
	{
		String text=item.trim();

		int as=text.toLowerCase(Locale.ROOT).lastIndexOf(" as ");
		if( as>=0 )
		{
			String alias=text.substring(as+4).trim();
			if( !alias.isBlank() )
			{
				return alias;
			}
		}

		if( text.regionMatches(true,0,"new ",0,4) )
		{
			String type=text.substring(4).trim();
			int paren=type.indexOf('(');
			if( paren>=0 )
			{
				type=type.substring(0,paren).trim();
			}
			int dot=type.lastIndexOf('.');
			return dot>=0?type.substring(dot+1):type;
		}

		if( text.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") )
		{
			int dot=text.lastIndexOf('.');
			return dot>=0?text.substring(dot+1):text;
		}

		return text.length()<=40?text:text.substring(0,40)+"...";
	}

	private List<String> _defaultHeaders(List<List<Object>> rows)
	{
		if( rows.isEmpty() )
		{
			return List.of();
		}
		int columns=rows.get(0).size();
		List<String> headers=new ArrayList<>(columns);
		for(int i=0;i<columns;i++)
		{
			headers.add("col"+(i+1));
		}
		return headers;
	}

	// ==================== mapeo de celdas ====================

	private List<List<Object>> _toRows(List<?> raw,EntityManagerFactory emf)
	{
		PersistenceUnitUtil util=emf.getPersistenceUnitUtil();
		List<EntityType<?>> entityTypes=new ArrayList<>(emf.getMetamodel().getEntities());
		List<List<Object>> rows=new ArrayList<>(raw.size());
		for(Object row:raw)
		{
			rows.add(_cells(row,util,entityTypes));
		}
		return rows;
	}

	private List<Object> _cells(Object row,PersistenceUnitUtil util,List<EntityType<?>> entityTypes)
	{
		Object[] values=_values(row);

		List<Object> cells=new ArrayList<>(values.length);
		for(Object value:values)
		{
			cells.add(_cell(value,util,entityTypes));
		}
		return cells;
	}

	/**
	 * Convierte un valor de la base en algo que Jackson pueda serializar sin sobresaltos.
	 * Lo que no es un escalar se describe: nunca se devuelve una entidad ni una colección cruda.
	 */
	private Object _cell(Object value,PersistenceUnitUtil util,List<EntityType<?>> entityTypes)
	{
		if( value==null )
		{
			return null;
		}
		if( value instanceof String string )
		{
			return string;
		}
		if( value instanceof Boolean||value instanceof Character )
		{
			return value.toString();
		}
		if( value instanceof Number number )
		{
			// NaN e Infinity no son JSON válido y romperían el JSON.parse del navegador.
			if( value instanceof Double d&&!Double.isFinite(d) )
			{
				return d.toString();
			}
			if( value instanceof Float f&&!Float.isFinite(f) )
			{
				return f.toString();
			}
			return number;
		}
		if( value instanceof Enum<?> enumeration )
		{
			return enumeration.name();
		}
		if( value instanceof byte[] bytes )
		{
			return "<"+bytes.length+" bytes>";
		}
		if( value instanceof Collection<?> )
		{
			// Sin .size(): un PersistentBag perezoso se inicializaría solo por preguntar.
			return "«colección»";
		}
		if( value instanceof Map<?,?> )
		{
			return "«mapa»";
		}
		if( value instanceof Date||value instanceof Calendar||value instanceof UUID )
		{
			return value.toString();
		}
		if( value.getClass().getName().startsWith("java.") )
		{
			// java.time.*, java.sql.Date, etc.
			return value.toString();
		}

		String entity=_entityLabel(value,util,entityTypes);
		return entity!=null?entity:"«"+value.getClass().getSimpleName()+"»";
	}

	/**
	 * Etiqueta {@code Tipo#id} para una entidad o un proxy sin inicializar. El tipo sale del
	 * metamodelo (un proxy ES instancia de la clase de la entidad) y el id de
	 * {@link PersistenceUnitUtil#getIdentifier}, que no dispara la carga del proxy.
	 */
	private String _entityLabel(Object value,PersistenceUnitUtil util,List<EntityType<?>> entityTypes)
	{
		String name=null;
		for(EntityType<?> type:entityTypes)
		{
			if( type.getJavaType().isInstance(value) )
			{
				name=type.getName();
				break;
			}
		}
		if( name==null )
		{
			return null;
		}

		Object id;
		try
		{
			id=util.getIdentifier(value);
		}
		catch(RuntimeException notAnEntity)
		{
			id=null;
		}
		return id==null?"«"+name+"»":name+"#"+id;
	}

	// ==================== infraestructura ====================

	private EntityManagerFactory _entityManagerFactory()
	{
		EntityManagerFactory emf=entityManagerFactory.getIfAvailable();
		if( emf==null )
		{
			throw new IllegalStateException("No hay ningún EntityManagerFactory en el contexto. "
					+"La consola HQL necesita JPA: agregá spring-boot-starter-data-jpa y configurá un datasource.");
		}
		return emf;
	}

	private EntityType<?> _entityType(EntityManagerFactory emf,String name)
	{
		Metamodel metamodel=emf.getMetamodel();
		for(EntityType<?> entityType:metamodel.getEntities())
		{
			if( entityType.getName().equals(name)
					||entityType.getJavaType().getSimpleName().equals(name)
					||entityType.getJavaType().getName().equals(name) )
			{
				return entityType;
			}
		}

		// Los nombres de entidad son case sensitive, igual que en HQL. Si lo único que falló fue
		// el case, decirlo ahorra el rato de buscar el error.
		String suggestion=null;
		for(EntityType<?> entityType:metamodel.getEntities())
		{
			if( entityType.getName().equalsIgnoreCase(name)
					||entityType.getJavaType().getSimpleName().equalsIgnoreCase(name) )
			{
				suggestion=entityType.getName();
				break;
			}
		}

		StringBuilder names=new StringBuilder();
		for(EntityType<?> entityType:metamodel.getEntities())
		{
			if( names.length()>0 )
			{
				names.append(", ");
			}
			names.append(entityType.getName());
		}
		throw new IllegalArgumentException("No conozco la entidad '"+name+"'."
				+(suggestion==null?"":" ¿Quisiste decir '"+suggestion+"'?")
				+" Las que hay son: "+names);
	}

	private Object _instantiate(EntityType<?> entityType)
	{
		Class<?> type=entityType.getJavaType();
		try
		{
			Constructor<?> constructor=type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		}
		catch(ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("No pude instanciar "+type.getName()
					+": una entidad necesita un constructor sin argumentos (puede ser protected)",e);
		}
	}

	private EntityTransaction _begin(EntityManager em)
	{
		EntityTransaction tx;
		try
		{
			tx=em.getTransaction();
		}
		catch(IllegalStateException e)
		{
			throw new IllegalStateException("Este datasource es JTA y la consola HQL ejecuta con transacciones "
					+"resource-local, así que no puede usarlo.",e);
		}
		tx.begin();
		return tx;
	}

	private void _rollbackQuietly(EntityTransaction tx)
	{
		try
		{
			if( tx.isActive() )
			{
				tx.rollback();
			}
		}
		catch(RuntimeException ignored)
		{
			// el error original es el que le importa al usuario
		}
	}

	/** El consultorio de siempre del {@code UPDATE} de la consola: sólo el tope global. */
	private void _limit(Query query)
	{
		_limite(query,maxRows,maxRows>0);
	}

	/**
	 * Aplica el tope de filas a la consulta. {@code tope<=0} es "sin tope".
	 *
	 * <p>{@code pedirUnaMas} pide una fila de más para poder distinguir "hay exactamente {@code tope}"
	 * de "hay más": es lo que hace que el aviso de truncado sea real y no una sospecha.</p>
	 */
	private void _limite(Query query,int tope,boolean pedirUnaMas)
	{
		if( tope>0 )
		{
			query.setMaxResults(pedirUnaMas&&tope<Integer.MAX_VALUE?tope+1:tope);
		}
	}

	private long _millis(long t0)
	{
		return (System.nanoTime()-t0)/1_000_000L;
	}
}
