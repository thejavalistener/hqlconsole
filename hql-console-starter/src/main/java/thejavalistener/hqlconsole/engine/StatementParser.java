package thejavalistener.hqlconsole.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser de las sentencias propias de la consola.
 *
 * <p>Es puramente sintáctico: no conoce el metamodelo ni la base, así que se puede leer de un
 * vistazo y probar sin levantar nada. Lo único que decide es <i>dónde termina cada parte</i>.</p>
 *
 * <p>{@link #parse(String)} devuelve {@code null} cuando la sentencia no es de la consola
 * (por ejemplo un {@code insert into ... select} de HQL de verdad) para que el que llama pueda
 * seguir por el camino de HQL. Si la sentencia <i>sí</i> es de la consola pero está mal escrita,
 * tira {@link IllegalArgumentException} con el detalle.</p>
 */
public final class StatementParser
{
	private StatementParser() {}

	public static Statement parse(String statement)
	{
		String text=statement.trim();

		if( Text.startsWithWord(text,"desc")||Text.startsWithWord(text,"describe") )
		{
			return _parseDesc(text);
		}
		if( Text.startsWithWord(text,"insert") )
		{
			return _parseInsert(text);
		}
		if( Text.startsWithWord(text,"update") )
		{
			return _parseUpdate(text);
		}
		return null;
	}

	// ==================== DESC ====================

	private static Statement _parseDesc(String text)
	{
		int after=Text.startsWithWord(text,"describe")?8:4;
		String rest=text.substring(after).trim();
		if( rest.isEmpty() )
		{
			// DESC sin argumentos: la lista de entidades.
			return new Statement(Statement.Kind.DESC,null,null,List.of(),null);
		}
		if( rest.indexOf(' ')>=0||rest.indexOf('\t')>=0 )
		{
			throw new IllegalArgumentException("DESC espera una sola entidad (o nada, para ver la lista): DESC [Entidad]");
		}
		return new Statement(Statement.Kind.DESC,rest,null,List.of(),null);
	}

	// ==================== INSERT ====================

	private static Statement _parseInsert(String text)
	{
		int values=Text.indexOfKeyword(text,"values");
		if( values<0 )
		{
			// No es la gramática de la consola: puede ser el "insert into ... select" de HQL.
			return null;
		}

		int into=Text.indexOfKeyword(text,"into",6);
		String head=text.substring(into<0?6:into+4,values).trim();
		String[] parts=_entityAndAlias(head,"INSERT INTO <Entidad> [alias] VALUES ...");

		List<Statement.Assignment> assignments=_parseAssignments(text.substring(values+6));
		if( assignments.isEmpty() )
		{
			throw new IllegalArgumentException("INSERT: VALUES no tiene ninguna asignación (esperaba campo=valor)");
		}
		return new Statement(Statement.Kind.INSERT,parts[0],parts[1],assignments,null);
	}

	// ==================== UPDATE ====================

	private static Statement _parseUpdate(String text)
	{
		int set=Text.indexOfKeyword(text,"set",6);
		if( set<0 )
		{
			return null;
		}

		String head=text.substring(6,set).trim();
		String[] parts=_entityAndAlias(head,"UPDATE <Entidad> [alias] SET ...");

		int where=Text.indexOfKeyword(text,"where",set+3);
		String setClause=where<0?text.substring(set+3):text.substring(set+3,where);
		String whereClause=where<0?null:text.substring(where+5).trim();
		if( whereClause!=null&&whereClause.isEmpty() )
		{
			throw new IllegalArgumentException("UPDATE: el WHERE quedó vacío (o lo sacás, o le ponés la condición)");
		}

		List<Statement.Assignment> assignments=_parseAssignments(setClause);
		if( assignments.isEmpty() )
		{
			throw new IllegalArgumentException("UPDATE: el SET no tiene ninguna asignación (esperaba campo=valor)");
		}
		return new Statement(Statement.Kind.UPDATE,parts[0],parts[1],assignments,whereClause);
	}

	// ==================== partes comunes ====================

	private static String[] _entityAndAlias(String head,String expected)
	{
		String[] parts=head.split("\\s+");
		// Se tolera el AS de HQL, que acá no hace falta pero no molesta.
		if( parts.length==3&&"as".equalsIgnoreCase(parts[1]) )
		{
			return new String[]{parts[0],parts[2]};
		}
		if( parts.length==1&&!parts[0].isEmpty() )
		{
			return new String[]{parts[0],null};
		}
		if( parts.length==2 )
		{
			return new String[]{parts[0],parts[1]};
		}
		throw new IllegalArgumentException("No entiendo '"+head+"' (esperaba: "+expected+")");
	}

	private static List<Statement.Assignment> _parseAssignments(String list)
	{
		List<Statement.Assignment> assignments=new ArrayList<>();
		for(String part:Text.splitTopLevel(list))
		{
			String item=part.trim();
			if( item.isEmpty() )
			{
				continue; // tolera una coma de más al final
			}
			int equals=Text.indexOfTopLevel(item,'=');
			if( equals<0 )
			{
				throw new IllegalArgumentException("No entiendo la asignación '"+item+"' (esperaba campo=valor)");
			}
			String path=item.substring(0,equals).trim();
			String literal=item.substring(equals+1).trim();
			if( path.isEmpty()||literal.isEmpty() )
			{
				throw new IllegalArgumentException("Asignación incompleta: '"+item+"'");
			}
			assignments.add(new Statement.Assignment(path,literal));
		}
		return assignments;
	}
}
