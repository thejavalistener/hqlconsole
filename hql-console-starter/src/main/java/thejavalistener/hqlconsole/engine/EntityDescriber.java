package thejavalistener.hqlconsole.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;

import static thejavalistener.hqlconsole.engine.HqlResult.ColumnType;

/**
 * Arma las respuestas de {@code DESC}.
 *
 * <p>{@code DESC <Entidad>} devuelve {@code ATRIBUTO | TIPO JAVA | CAMPO | TIPO SQL}, en ese orden:
 * primero lo que uno escribe en una sentencia (el atributo y su tipo Java) y después lo que existe
 * en la base (la columna física y su tipo SQL). Las dos últimas salen de la <b>base</b>, preguntando
 * por JDBC ({@code DatabaseMetaData}): el metamodelo de JPA no expone nombres de columna ni tipos
 * SQL, y los que se deducen del mapping pueden no coincidir con lo que hay realmente en la tabla. Si
 * no hay {@code DataSource} en el contexto o la tabla no aparece, cae a lo derivado del mapping en
 * vez de fallar.</p>
 *
 * <p>{@code DESC} sin argumentos devuelve la lista de entidades.</p>
 *
 * <p>El metamodelo se pasa por parámetro en vez de guardarlo en un campo: este bean es un
 * singleton y lo pueden estar usando varias requests a la vez.</p>
 */
public class EntityDescriber
{
	private static final Logger log=LoggerFactory.getLogger(EntityDescriber.class);

	private static final List<String> HEADERS=List.of("ATRIBUTO","TIPO JAVA","CAMPO","TIPO SQL");
	private static final List<String> LIST_HEADERS=List.of("ENTIDAD","TABLA","CAMPOS");

	private final ObjectProvider<DataSource> dataSource;

	public EntityDescriber(ObjectProvider<DataSource> dataSource)
	{
		this.dataSource=dataSource;
	}

	/** {@code DESC} sin argumentos: todas las entidades del contexto. */
	public HqlResult describeEntities(Metamodel metamodel,long t0)
	{
		List<EntityType<?>> entities=new ArrayList<>(metamodel.getEntities());
		entities.sort(Comparator.comparing(EntityType::getName));

		List<List<Object>> rows=new ArrayList<>(entities.size());
		for(EntityType<?> entityType:entities)
		{
			rows.add(List.of(
					entityType.getName(),
					Mapping.physicalName(Mapping.tableName(entityType)),
					String.valueOf(Mapping.columnsInDeclarationOrder(entityType).size())));
		}
		return HqlResult.query(LIST_HEADERS,_tipos(ColumnType.TEXTO,ColumnType.TEXTO,ColumnType.NUMERO),rows,false,_millis(t0),
				entities.size()+" entidad(es). Hacé DESC <Entidad> para ver sus columnas.");
	}

	public HqlResult describe(Metamodel metamodel,EntityType<?> entityType,long t0)
	{
		Map<String,SqlColumn> columns=_columnsFromDatabase(entityType);
		List<Attribute<?,?>> attributes=new ArrayList<>(entityType.getAttributes());
		attributes.sort(Comparator.comparingInt(attribute -> {
			int index=Mapping.declarationOrder(entityType.getJavaType()).indexOf(Mapping.memberName(attribute));
			return index<0?Integer.MAX_VALUE:index;
		}));

		List<List<Object>> rows=new ArrayList<>(attributes.size());
		for(Attribute<?,?> attribute:attributes)
		{
			boolean isColumn=Mapping.isColumn(attribute);
			String derived=isColumn?Mapping.columnName(metamodel,attribute):"-";
			SqlColumn real=isColumn?columns.get(derived.toLowerCase(Locale.ROOT)):null;

			// Convención de la consola: las tablas y las columnas (cosas de la base) se muestran en
			// MAYÚSCULAS cuando vienen en un solo caso; los atributos y las clases, tal cual están.
			String campo=Mapping.physicalName(real!=null?real.name():derived);
			String sqlType=real!=null?real.typeName():(isColumn?_derivedSqlType(metamodel,attribute):"-");

			// El orden es el de la sentencia: ATRIBUTO y TIPO JAVA son lo que uno escribe, CAMPO y
			// TIPO SQL son cómo se llama y qué es eso en la base.
			rows.add(List.of(attribute.getName(),Mapping.javaTypeName(attribute),campo,sqlType));
		}
		// Todos los tipos de esta grilla son texto: son nombres. Ordenar por "TIPO JAVA" o por
		// "CAMPO" es ordenar alfabéticamente, que es exactamente lo que se espera.
		return HqlResult.query(HEADERS,_tipos(ColumnType.TEXTO,ColumnType.TEXTO,ColumnType.TEXTO,ColumnType.TEXTO),
				rows,false,_millis(t0));
	}

	/** Los tipos de una grilla, en el orden de sus columnas. */
	private static List<String> _tipos(HqlResult.ColumnType... tipos)
	{
		List<String> nombres=new ArrayList<>(tipos.length);
		for(HqlResult.ColumnType tipo:tipos)
		{
			nombres.add(tipo.name());
		}
		return nombres;
	}

	/** Una columna tal como la reporta la base. */
	private record SqlColumn(String name,String typeName) {}

	// ==================== metadata real de la base ====================

	private Map<String,SqlColumn> _columnsFromDatabase(EntityType<?> entityType)
	{
		DataSource source=dataSource.getIfAvailable();
		if( source==null )
		{
			return Map.of();
		}

		Set<String> candidates=_tableNameCandidates(entityType);
		try( Connection connection=source.getConnection() )
		{
			DatabaseMetaData metadata=connection.getMetaData();
			String schema=null;
			try
			{
				schema=connection.getSchema();
			}
			catch(RuntimeException notSupported)
			{
				schema=null;
			}

			for(String table:candidates)
			{
				Map<String,SqlColumn> found=_columns(metadata,schema,table);
				if( found.isEmpty() )
				{
					found=_columns(metadata,null,table);
				}
				if( !found.isEmpty() )
				{
					return found;
				}
			}
			log.debug("El DESC no encontró la tabla de {} entre {}; se usa el mapping",entityType.getName(),candidates);
		}
		catch(SQLException e)
		{
			log.debug("El DESC no pudo leer la metadata de la base: {}",e.getMessage());
		}
		return Map.of();
	}

	private Map<String,SqlColumn> _columns(DatabaseMetaData metadata,String schema,String table) throws SQLException
	{
		Map<String,SqlColumn> columns=new LinkedHashMap<>();
		try( ResultSet result=metadata.getColumns(null,schema,table,null) )
		{
			while( result.next() )
			{
				String name=result.getString("COLUMN_NAME");
				columns.put(name.toLowerCase(Locale.ROOT),new SqlColumn(name,result.getString("TYPE_NAME")));
			}
		}
		return columns;
	}

	/** El nombre físico de la tabla no lo expone JPA: se prueban las variantes razonables. */
	private Set<String> _tableNameCandidates(EntityType<?> entityType)
	{
		Set<String> bases=new LinkedHashSet<>();
		bases.add(Mapping.tableName(entityType));
		bases.add(Mapping.snakeCase(entityType.getName()));
		bases.add(Mapping.snakeCase(entityType.getJavaType().getSimpleName()));

		Set<String> candidates=new LinkedHashSet<>();
		for(String base:bases)
		{
			candidates.add(base);
			candidates.add(base.toUpperCase(Locale.ROOT));
			candidates.add(base.toLowerCase(Locale.ROOT));
		}
		return candidates;
	}

	// ==================== derivado del mapping (fallback) ====================

	private String _derivedSqlType(Metamodel metamodel,Attribute<?,?> attribute)
	{
		if( Mapping.isToOne(attribute) )
		{
			EntityType<?> related=Mapping.relatedEntity(metamodel,attribute);
			return related==null?"INTEGER":_derivedSqlType(metamodel,Mapping.idOf(related));
		}

		Class<?> type=attribute.getJavaType();
		Column column=Mapping.annotation(attribute,Column.class);

		if( Mapping.annotation(attribute,Lob.class)!=null )
		{
			return type==String.class?"CLOB":"BLOB";
		}
		if( column!=null&&!column.columnDefinition().isEmpty() )
		{
			return column.columnDefinition();
		}
		if( type.isEnum() )
		{
			Enumerated enumerated=Mapping.annotation(attribute,Enumerated.class);
			return enumerated!=null&&enumerated.value()==EnumType.STRING?"VARCHAR":"INTEGER";
		}
		Temporal temporal=Mapping.annotation(attribute,Temporal.class);
		if( temporal!=null )
		{
			return temporal.value()==TemporalType.DATE?"DATE"
					:temporal.value()==TemporalType.TIME?"TIME":"TIMESTAMP";
		}

		if( type==String.class||type==Character.class||type==char.class )
		{
			return column!=null&&column.length()>0?"VARCHAR("+column.length()+")":"VARCHAR";
		}
		if( type==Integer.class||type==int.class )
		{
			return "INTEGER";
		}
		if( type==Long.class||type==long.class )
		{
			return "BIGINT";
		}
		if( type==Short.class||type==short.class )
		{
			return "SMALLINT";
		}
		if( type==Byte.class||type==byte.class )
		{
			return "TINYINT";
		}
		if( type==Boolean.class||type==boolean.class )
		{
			return "BOOLEAN";
		}
		if( type==BigDecimal.class||type==BigInteger.class )
		{
			return column!=null&&column.precision()>0?"DECIMAL("+column.precision()+","+column.scale()+")":"DECIMAL";
		}
		if( type==Double.class||type==double.class )
		{
			return "DOUBLE";
		}
		if( type==Float.class||type==float.class )
		{
			return "REAL";
		}
		if( type==LocalDate.class||type==java.sql.Date.class )
		{
			return "DATE";
		}
		if( type==LocalTime.class||type==java.sql.Time.class )
		{
			return "TIME";
		}
		if( type==LocalDateTime.class||type==Instant.class||type==OffsetDateTime.class||type==ZonedDateTime.class
				||type==java.sql.Timestamp.class||type==java.util.Date.class )
		{
			return "TIMESTAMP";
		}
		if( type==UUID.class )
		{
			return "UUID";
		}
		if( type==byte[].class||type==Byte[].class )
		{
			return "VARBINARY";
		}
		return type.getSimpleName().toUpperCase(Locale.ROOT);
	}

	private long _millis(long t0)
	{
		return (System.nanoTime()-t0)/1_000_000L;
	}
}
