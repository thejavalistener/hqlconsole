package thejavalistener.hqlconsole.web;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import thejavalistener.hqlconsole.autoconfigure.HqlConsoleProperties;
import thejavalistener.hqlconsole.engine.HqlQueryRunner;

/**
 * Interfaz HTTP de la consola: la página en {@code hql-console.path} y el endpoint que ejecuta
 * HQL en {@code {path}/api/execute}.
 *
 * <p>Los dos métodos llevan {@code @ResponseBody} a propósito. Si no, en una aplicación con
 * Thymeleaf o JSP el {@code String} del HTML se interpretaría como nombre de vista y la consola
 * devolvería un error de template en vez de la página.</p>
 */
@Controller
public class HqlConsoleController
{
	private final HqlQueryRunner runner;
	private final HqlConsoleProperties properties;

	public HqlConsoleController(HqlQueryRunner runner,HqlConsoleProperties properties)
	{
		this.runner=runner;
		this.properties=properties;
	}

	/** La página de la consola. Se mapean las dos variantes para tolerar la barra final. */
	@GetMapping(path={"${hql-console.path:/hqlconsole}","${hql-console.path:/hqlconsole}/"},
	            produces=MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public String page(HttpServletRequest request,HttpServletResponse response)
	{
		// La página no se cachea a propósito. Sin esta cabecera el navegador se queda con el HTML
		// viejo y parece que el jar no se actualizó: se cambia la consola, se reconstruye y en
		// pantalla sigue la versión anterior hasta un Ctrl+F5.
		response.setHeader("Cache-Control","no-store, no-cache, must-revalidate");
		response.setHeader("Pragma","no-cache");

		String path=properties.normalizedPath();
		// El context-path de la aplicación (server.servlet.context-path) no está dentro del mapeo
		// del controller, así que el fetch de la página lo necesita explícito.
		String base=request.getContextPath()+path;
		return HqlConsolePage.html(base,properties.getMaxRows(),properties.isAllowWrites());
	}

	/** Ejecuta una sentencia HQL contra el EntityManager vivo. */
	@PostMapping(path="${hql-console.path:/hqlconsole}/api/execute",
	             consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
	@ResponseBody
	public ResponseEntity<Object> execute(@RequestBody(required=false) Map<String,Object> body)
	{
		Object raw=body==null?null:body.get("hql");
		if( !(raw instanceof String hql)||hql.isBlank() )
		{
			return ResponseEntity.badRequest().body(Map.of("error","Falta el campo 'hql'."));
		}

		if( _isWrite(hql)&&!properties.isAllowWrites() )
		{
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(Map.of("error","Las sentencias de escritura están bloqueadas (hql-console.allow-writes=false).",
							     "statement",hql.trim()));
		}

		try
		{
			return ResponseEntity.ok(runner.execute(hql));
		}
		catch(Exception e)
		{
			return ResponseEntity.badRequest().body(_error(e,hql));
		}
	}

	// ==================== errores ====================

	private Map<String,Object> _error(Exception e,String hql)
	{
		Map<String,Object> out=new LinkedHashMap<>();
		out.put("error",e.getMessage()==null||e.getMessage().isBlank()?e.toString():e.getMessage());

		String root=_rootCauseMessage(e);
		if( root!=null )
		{
			out.put("cause",root);
		}

		out.put("exception",e.getClass().getSimpleName());
		out.put("statement",hql.trim());
		if( properties.isShowStacktrace() )
		{
			out.put("stacktrace",_stacktrace(e));
		}
		return out;
	}

	/** El mensaje de la causa raíz suele ser el útil ("expecting IDENT, found '*'"). */
	private String _rootCauseMessage(Throwable throwable)
	{
		Throwable cause=throwable;
		while( cause.getCause()!=null&&cause.getCause()!=cause )
		{
			cause=cause.getCause();
		}
		String message=cause.getMessage();
		if( message==null||message.isBlank()||message.equals(throwable.getMessage()) )
		{
			return null;
		}
		return message;
	}

	private String _stacktrace(Throwable throwable)
	{
		StringWriter writer=new StringWriter();
		throwable.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

	private boolean _isWrite(String hql)
	{
		String first=_firstWord(hql.trim());
		return "insert".equalsIgnoreCase(first)||"update".equalsIgnoreCase(first)||"delete".equalsIgnoreCase(first);
	}

	private String _firstWord(String statement)
	{
		int i=0;
		while( i<statement.length()&&Character.isWhitespace(statement.charAt(i)) )
		{
			i++;
		}
		int start=i;
		while( i<statement.length()&&!Character.isWhitespace(statement.charAt(i)) )
		{
			i++;
		}
		return statement.substring(start,i).toLowerCase(Locale.ROOT);
	}
}
