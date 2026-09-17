package thejavalistener.hqlconsole.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

/**
 * Avisa en el log, al arrancar, dónde quedó la consola.
 *
 * <p>Este banner respeta dos reglas a rajatabla, porque es puramente cosmético:</p>
 *
 * <ol>
 *   <li><b>No referencia ninguna clase interna de Spring Boot.</b> La primera versión usaba
 *       {@code ServletWebServerApplicationContext} y {@code WebServer}. Spring Boot 4.0 movió esos
 *       paquetes ({@code org.springframework.boot.web.servlet.context} pasó a
 *       {@code org.springframework.boot.web.server.servlet.context}), así que en una aplicación
 *       Boot 4 el banner moría con {@code NoClassDefFoundError}. El puerto ahora sale de la
 *       propiedad {@code local.server.port}, que publica la propia aplicación cuando el server web
 *       arranca —es la misma que resuelve {@code @LocalServerPort}—, con {@code server.port} como
 *       respaldo. Sólo se usan nombres de propiedad, no clases.</li>
 *   <li><b>No puede tumbar el arranque.</b> Un {@code ApplicationRunner} que lanza una excepción
 *       hace fallar {@code SpringApplication.run}. Un mensaje informativo no tiene derecho a eso,
 *       así que todo va envuelto en {@code catch(Throwable)}.</li>
 * </ol>
 */
public class HqlConsoleBanner implements ApplicationRunner
{
	private static final Logger log=LoggerFactory.getLogger(HqlConsoleBanner.class);

	private final Environment environment;
	private final HqlConsoleProperties properties;

	public HqlConsoleBanner(Environment environment,HqlConsoleProperties properties)
	{
		this.environment=environment;
		this.properties=properties;
	}

	@Override
	public void run(ApplicationArguments args)
	{
		try
		{
			String port=_property("local.server.port",_property("server.port","8080"));
			String contextPath=_contextPath();

			log.warn("Consola HQL en http://localhost:{}{}{}  [escrituras {}] — herramienta de desarrollo, no la dejes habilitada en producción",
					port,contextPath,properties.normalizedPath(),
					properties.isAllowWrites()?"HABILITADAS":"bloqueadas");
		}
		catch(Throwable t)
		{
			// Nunca abortamos el arranque de la aplicación por un mensaje informativo.
			log.debug("No se pudo anunciar la URL de la consola HQL",t);
		}
	}

	/** Normalizado para que "/" y "" den lo mismo y nunca queden barras duplicadas. */
	private String _contextPath()
	{
		String contextPath=_property("server.servlet.context-path","");
		while( contextPath.endsWith("/") )
		{
			contextPath=contextPath.substring(0,contextPath.length()-1);
		}
		return contextPath.isEmpty()||contextPath.startsWith("/")?contextPath:"/"+contextPath;
	}

	private String _property(String name,String fallback)
	{
		String value=environment.getProperty(name);
		return value==null||value.isBlank()?fallback:value.trim();
	}
}
