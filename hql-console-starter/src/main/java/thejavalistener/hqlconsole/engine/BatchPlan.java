package thejavalistener.hqlconsole.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Valida la forma de un lote de escrituras antes de abrir una transacción.
 *
 * <p>{@code SET AUTOCOMMIT ON} no cambia una conexión ni queda guardado: es una directiva del
 * lote actual para que la interfaz no pida su confirmación única. Debe ser su primera sentencia
 * y siempre tiene que acompañar por lo menos una escritura.</p>
 */
public record BatchPlan(List<String> statements, boolean autoCommit)
{
	public static BatchPlan parse(List<String> input)
	{
		if( input==null||input.size()<2 )
		{
			throw new IllegalArgumentException("Un lote necesita al menos dos sentencias.");
		}

		boolean autoCommit=false;
		List<String> writes=new ArrayList<>();
		for(int i=0;i<input.size();i++)
		{
			String statement=input.get(i);
			if( isAutoCommitOn(statement) )
			{
				if( i!=0 )
				{
					throw new IllegalArgumentException("SET AUTOCOMMIT ON sólo puede ser la primera sentencia del lote.");
				}
				autoCommit=true;
				continue;
			}

			String first=Text.firstWord(statement);
			if( !"insert".equalsIgnoreCase(first)&&!"update".equalsIgnoreCase(first)
					&& !"delete".equalsIgnoreCase(first) )
			{
				throw new IllegalArgumentException("La sentencia "+(i+1)+" de "+input.size()
						+" no es INSERT, UPDATE ni DELETE (empieza con '"+first+"').");
			}
			writes.add(statement);
		}
		if( writes.isEmpty() )
		{
			throw new IllegalArgumentException("SET AUTOCOMMIT ON sólo es válido dentro de un lote con escrituras.");
		}
		return new BatchPlan(List.copyOf(writes),autoCommit);
	}

	public static boolean isAutoCommitOn(String statement)
	{
		return statement!=null&&statement.trim().matches("(?i)set\\s+autocommit\\s+on");
	}
}
