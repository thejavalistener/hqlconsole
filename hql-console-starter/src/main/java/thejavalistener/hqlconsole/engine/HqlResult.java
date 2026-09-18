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
 */
public record HqlResult(String type,List<String> headers,List<List<Object>> rows,int rowCount,
                        int affectedRows,int statementCount,boolean truncated,long elapsedMs,String message)
{
	public static HqlResult query(List<String> headers,List<List<Object>> rows,boolean truncated,long elapsedMs)
	{
		return query(headers,rows,truncated,elapsedMs,null);
	}

	public static HqlResult query(List<String> headers,List<List<Object>> rows,boolean truncated,long elapsedMs,String message)
	{
		return new HqlResult("QUERY",headers,rows,rows.size(),0,0,truncated,elapsedMs,message);
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
		return new HqlResult("DML",List.of(),List.of(),0,affectedRows,1,truncated,elapsedMs,message);
	}

	/** Varias sentencias de escritura en una sola transacción: o entran todas o no entra ninguna. */
	public static HqlResult batch(int affectedRows,int statementCount,long elapsedMs,String message)
	{
		return new HqlResult("BATCH",List.of(),List.of(),0,affectedRows,statementCount,false,elapsedMs,message);
	}
}
