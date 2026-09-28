package thejavalistener.hqlconsole.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Renderiza la plantilla HTML empaquetada de la consola. */
public final class HqlConsolePage
{
	private static final String RESOURCE="/thejavalistener/hqlconsole/web/hql-console.html";
	private static final String HELP_RESOURCE="/thejavalistener/hqlconsole/web/hql-console-help.html";
	private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
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

	/** Manual breve ya renderizado y empaquetado con el starter; no depende de Markdown en el navegador. */
	public static String help()
	{
		try(InputStream input=HqlConsolePage.class.getResourceAsStream(HELP_RESOURCE))
		{
			if( input==null ) throw new IllegalStateException("No encontré el manual de ayuda en el classpath.");
			return new String(input.readAllBytes(),StandardCharsets.UTF_8);
		}
		catch(IOException e)
		{
			throw new IllegalStateException("No pude leer el manual de ayuda.",e);
		}
	}

	/**
	 * Lee el manual remoto y, ante cualquier problema de red o contenido, conserva el manual
	 * empaquetado. El controller cachea el resultado por sesión para no consultar la red más de una vez.
	 */
	public static String help(String remoteUrl)
	{
		if( remoteUrl==null||remoteUrl.isBlank() ) return help();
		try
		{
			HttpRequest request=HttpRequest.newBuilder(URI.create(remoteUrl))
					.timeout(Duration.ofSeconds(5))
					.header("Accept","text/markdown, text/plain;q=0.9, text/html;q=0.8")
					.GET().build();
			HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if( response.statusCode()>=200&&response.statusCode()<300&&!response.body().isBlank() )
			{
				return response.body();
			}
		}
		catch(Exception ignored)
		{
			// La ayuda nunca debe romper la consola porque GitHub o la red estén caídos.
		}
		return help();
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
