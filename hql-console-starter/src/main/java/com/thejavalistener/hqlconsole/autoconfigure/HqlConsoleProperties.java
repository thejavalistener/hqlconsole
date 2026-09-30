package com.thejavalistener.hqlconsole.autoconfigure;

import java.net.URI;
import java.net.URISyntaxException;

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
	/** Manual HTML publicado por la propia consola; la aplicación anfitriona no debe configurarlo. */
	private static final String DEFAULT_HELP_URL="https://raw.githubusercontent.com/thejavalistener/hqlconsole/main/docs/help.md";

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

	/** URL HTTPS del manual Markdown dinámico. Vacía fuerza el respaldo empaquetado. */
	private String helpUrl=DEFAULT_HELP_URL;

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

	/**
	 * URL del manual que abre el botón Ayuda en una pestaña nueva.
	 *
	 * <p>Por defecto apunta al Markdown publicado por la consola: actualizar ese archivo no exige
	 * recompilar ni configurar la aplicación anfitriona. Sólo se admite HTTPS; si está vacía, se
	 * usa el manual HTML que trae el starter.</p>
	 */
	public String normalizedHelpUrl()
	{
		String value=helpUrl==null?"":helpUrl.trim();
		if( value.isEmpty() ) return "";
		try
		{
			URI uri=new URI(value);
			if( !"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null )
			{
				throw new IllegalArgumentException("hql-console.help-url debe ser una URL HTTPS válida o quedar vacía.");
			}
			return uri.toASCIIString();
		}
		catch(URISyntaxException e)
		{
			throw new IllegalArgumentException("hql-console.help-url debe ser una URL HTTPS válida o quedar vacía.",e);
		}
	}

	public String getHelpUrl()
	{
		return helpUrl;
	}

	public void setHelpUrl(String helpUrl)
	{
		this.helpUrl=helpUrl;
	}
}
