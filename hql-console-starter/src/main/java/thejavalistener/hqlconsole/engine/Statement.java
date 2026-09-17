package thejavalistener.hqlconsole.engine;

import java.util.List;

/**
 * Una sentencia propia de la consola (no es HQL): {@code INSERT ... VALUES}, {@code UPDATE ... SET}
 * o {@code DESC}.
 *
 * <p>El {@code where} viaja <b>textual</b> a Hibernate: no lo parseamos, lo pegamos en un
 * {@code select} y dejamos que lo resuelva el mismo parser que resuelve cualquier consulta. Eso es
 * lo que hace que {@code AND}, {@code OR}, {@code IN}, {@code LIKE}, {@code BETWEEN} y las
 * subconsultas funcionen sin que esta clase sepa nada de expresiones.</p>
 */
public record Statement(Kind kind,String entity,String alias,List<Assignment> assignments,String where)
{
	public enum Kind
	{
		INSERT, UPDATE, DESC
	}

	/** {@code path} es lo que está a la izquierda del "=" y {@code literal} lo de la derecha. */
	public record Assignment(String path,String literal) {}

	public boolean isWrite()
	{
		return kind==Kind.INSERT||kind==Kind.UPDATE;
	}
}
