package thejavalistener.hqlconsole.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Renderiza la plantilla HTML empaquetada de la consola. */
public final class HqlConsolePage
{
	private static final String RESOURCE="/thejavalistener/hqlconsole/web/hql-console.html";
	private static final String CONFIG_MARKER="__HQL_CONSOLE_CONFIG__";
	private static final String TEMPLATE=_loadTemplate();

	private HqlConsolePage()
	{
	}

	/** Devuelve la página con su configuración dependiente de la aplicación anfitriona. */
	public static String html(String base,int maxRows)
	{
		return render(TEMPLATE,base,maxRows);
	}

	static String render(String template,String base,int maxRows)
	{
		if( _count(template,CONFIG_MARKER)!=1 )
		{
			throw new IllegalArgumentException("La plantilla HTML debe tener exactamente un marcador de configuración.");
		}
		return template.replace(CONFIG_MARKER,HqlConsolePageConfiguration.json(base,maxRows));
	}

	private static String _loadTemplate()
	{
		try(InputStream input=HqlConsolePage.class.getResourceAsStream(RESOURCE))
		{
			if( input==null )
			{
				throw new IllegalStateException("No encontré la plantilla HTML en el classpath: "+RESOURCE);
			}
			String template=new String(input.readAllBytes(),StandardCharsets.UTF_8);
			if( _count(template,CONFIG_MARKER)!=1 )
			{
				throw new IllegalStateException("La plantilla HTML debe tener exactamente un marcador de configuración.");
			}
			return template;
		}
		catch(IOException e)
		{
			throw new IllegalStateException("No pude leer la plantilla HTML: "+RESOURCE,e);
		}
	}

	private static int _count(String value,String fragment)
	{
		int count=0;
		int offset=0;
		while( (offset=value.indexOf(fragment,offset))>=0 )
		{
			count++;
			offset+=fragment.length();
		}
		return count;
	}
}
