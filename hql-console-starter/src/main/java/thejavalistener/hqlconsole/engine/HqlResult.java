package thejavalistener.hqlconsole.engine;

import java.util.List;

/**
 * Resultado de una sentencia, ya listo para serializar a JSON.
 *
 * <p>{@code type} es {@code QUERY} (lectura: select, from, desc) o {@code DML} (escritura). Las
 * celdas ya vienen saneadas por {@link HqlQueryRunner}: nunca contienen entidades crudas, así que
 * Jackson no puede caer en una recursión infinita ni disparar una carga perezosa fuera de sesión.
 * {@code message} es un detalle opcional para mostrar (el id generado, cuántas filas se tocaron).</p>
 */
public record HqlResult(String type,List<String> headers,List<List<Object>> rows,int rowCount,
                        int affectedRows,boolean truncated,long elapsedMs,String message)
{
	public static HqlResult query(List<String> headers,List<List<Object>> rows,boolean truncated,long elapsedMs)
	{
		return query(headers,rows,truncated,elapsedMs,null);
	}

	public static HqlResult query(List<String> headers,List<List<Object>> rows,boolean truncated,long elapsedMs,String message)
	{
		return new HqlResult("QUERY",headers,rows,rows.size(),0,truncated,elapsedMs,message);
	}

	public static HqlResult dml(String statement,int affectedRows,long elapsedMs,String message)
	{
		return new HqlResult("DML",List.of(),List.of(),0,affectedRows,false,elapsedMs,message);
	}
}
