package thejavalistener.hqlconsole.engine;

import java.util.List;

/**
 * Resultado de una sentencia, ya listo para serializar a JSON.
 *
 * <p>{@code type} es {@code QUERY} (lectura: select, from, desc), {@code DML} (una escritura) o
 * {@code BATCH} (varias escrituras en una sola transacción). Las celdas ya vienen saneadas por
 * {@link HqlQueryRunner}: nunca contienen entidades crudas, así que Jackson no puede caer en una
 * recursión infinita ni disparar una carga perezosa fuera de sesión. {@code message} es un detalle
 * opcional para mostrar (el id generado, cuántas filas se tocaron). {@code statementCount} es
 * cuántas sentencias se ejecutaron: 0 en una consulta, 1 en un DML suelto y N en un lote.</p>
 *
 * <p>{@code types} dice, por columna, <b>qué es</b> la celda: {@link ColumnType#TEXTO},
 * {@link ColumnType#NUMERO}, {@link ColumnType#FECHA}, {@link ColumnType#BOOLEANO} u
 * {@link ColumnType#OTRO}. Es lo que permite ordenar la grilla por una columna sin adivinar el tipo
 * mirando el texto: una fecha ISO ordenada como texto da otro resultado que ordenada como fecha. Si
 * no se puede deducir, o si el resultado no es una grilla, va la lista vacía y el que la use se
 * arregla como pueda.</p>
 */
public record HqlResult(String type,List<String> headers,List<String> types,List<List<Object>> rows,
                        int rowCount,int affectedRows,int statementCount,boolean truncated,long elapsedMs,
                        String message)
{
	/**
	 * Qué es una columna, para poder ordenarla con criterio sin que el cliente tenga que mirar el
	 * valor de cada celda. Es el grano justo: no hace falta el tipo Java exacto, sólo saber cómo se
	 * compara.
	 */
	public enum ColumnType
	{
		TEXTO, NUMERO, FECHA, BOOLEANO, OTRO
	}

	public static HqlResult query(List<String> headers,List<String> types,List<List<Object>> rows,
	                              boolean truncated,long elapsedMs)
	{
		return query(headers,types,rows,truncated,elapsedMs,null);
	}

	public static HqlResult query(List<String> headers,List<String> types,List<List<Object>> rows,
	                              boolean truncated,long elapsedMs,String message)
	{
		return new HqlResult("QUERY",headers,types(types,headers),rows,rows.size(),0,0,truncated,elapsedMs,message);
	}

	public static HqlResult dml(String statement,int affectedRows,long elapsedMs,String message)
	{
		return dml(statement,affectedRows,false,elapsedMs,message);
	}

	/**
	 * Un DML que puede haber quedado corto por el tope de filas: el {@code UPDATE} sin {@code WHERE}
	 * avisa así, y la página lo usa para avisar antes de confirmar que no va a tocar todo.
	 */
	public static HqlResult dml(String statement,int affectedRows,boolean truncated,long elapsedMs,String message)
	{
		return new HqlResult("DML",List.of(),List.of(),List.of(),0,affectedRows,1,truncated,elapsedMs,message);
	}

	/** Varias sentencias de escritura en una sola transacción: o entran todas o no entra ninguna. */
	public static HqlResult batch(int affectedRows,int statementCount,long elapsedMs,String message)
	{
		return new HqlResult("BATCH",List.of(),List.of(),List.of(),0,affectedRows,statementCount,false,elapsedMs,message);
	}

	/**
	 * La lista de tipos tiene que tener un elemento por columna: si no, se descarta entera en vez de
	 * dejar tipos corridos (que ordenarían por el criterio de otra columna).
	 */
	private static List<String> types(List<String> types,List<String> headers)
	{
		if( types==null||headers==null||types.size()!=headers.size() )
		{
			return List.of();
		}
		return types;
	}
}
