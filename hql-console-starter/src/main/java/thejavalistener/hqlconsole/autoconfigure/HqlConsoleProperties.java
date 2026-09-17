package thejavalistener.hqlconsole.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la consola, bajo el prefijo {@code hql-console}.
 *
 * <p>Está encendida por defecto: la gracia es tirar el jar y que la página aparezca. Para dejarla
 * inerte (por ejemplo en producción) alcanza con {@code hql-console.enabled=false}.</p>
 */
@ConfigurationProperties(prefix="hql-console")
public class HqlConsoleProperties
{
	/** Habilita la consola. */
	private boolean enabled=true;

	/** Ruta base de la consola. Tiene que empezar con "/". */
	private String path="/hqlconsole";

	/** Tope de filas que devuelve una consulta (0 = sin tope). */
	private int maxRows=500;

	/** Habilita las sentencias de escritura (insert / update / delete). */
	private boolean allowWrites=true;

	/** Incluye el stacktrace completo en la respuesta de error. */
	private boolean showStacktrace=false;

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled=enabled;
	}

	public String getPath()
	{
		return path;
	}

	/**
	 * La ruta base ya lista para usar: con "/" adelante y sin "/" al final.
	 *
	 * <p>Ojo: el mapeo del controller se resuelve con el placeholder crudo
	 * ({@code ${hql-console.path:/hqlconsole}}), así que si configurás {@code path} tiene que
	 * empezar con "/".</p>
	 */
	public String normalizedPath()
	{
		String value=path==null||path.isBlank()?"/hqlconsole":path.trim();
		if( !value.startsWith("/") )
		{
			value="/"+value;
		}
		while( value.length()>1&&value.endsWith("/") )
		{
			value=value.substring(0,value.length()-1);
		}
		return value;
	}

	public void setPath(String path)
	{
		this.path=path;
	}

	public int getMaxRows()
	{
		return maxRows;
	}

	public void setMaxRows(int maxRows)
	{
		this.maxRows=maxRows;
	}

	public boolean isAllowWrites()
	{
		return allowWrites;
	}

	public void setAllowWrites(boolean allowWrites)
	{
		this.allowWrites=allowWrites;
	}

	public boolean isShowStacktrace()
	{
		return showStacktrace;
	}

	public void setShowStacktrace(boolean showStacktrace)
	{
		this.showStacktrace=showStacktrace;
	}
}
