package thejavalistener.hqlconsole.web;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
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
import thejavalistener.hqlconsole.engine.BatchPlan;
import thejavalistener.hqlconsole.engine.Text;

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
		return HqlConsolePage.html(base,properties.getMaxRows());
	}

	/**
	 * Ejecuta HQL contra el EntityManager vivo.
	 *
	 * <p>El texto se parte por punto y coma <b>antes</b> de parsear: una sola sentencia se comporta
	 * como siempre (y de paso se le saca el {@code ;} final, que si no termina dentro del último
	 * valor), y varias se ejecutan como lote de INSERT en una única transacción.</p>
	 */
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

		// Los comentarios se sacan ANTES de partir por ';' y de parsear: así un ';' adentro de un
		// comentario no parte la sentencia, y lo que llega al motor (y al control de escrituras) es
		// sólo la sentencia. El texto original se conserva aparte para el mensaje de error.
		String language=body!=null&&body.get("language") instanceof String value?value:"hql";
		String texto=Text.withoutComments(hql);
		if( texto.isBlank() )
		{
			return ResponseEntity.badRequest().body(Map.of("error","La sentencia quedó vacía: sólo tenía comentarios."));
		}

		List<String> statements=Text.splitStatements(texto);
		if( statements.isEmpty() )
		{
			return ResponseEntity.badRequest().body(Map.of("error","No hay ninguna sentencia para ejecutar."));
		}

		if( "sql".equalsIgnoreCase(language) )
		{
			if( statements.size()!=1 )
			{
				return ResponseEntity.badRequest().body(Map.of("error","En modo SQL se ejecuta una sentencia por vez.",
						"statement",hql.trim()));
			}
			String statement=statements.get(0);
			String first=_firstWord(statement);
			try
			{
				if( "desc".equals(first)||"describe".equals(first) )
				{
					return ResponseEntity.ok(runner.executeSqlDesc(statement));
				}
				if( !"select".equals(first) )
				{
					return ResponseEntity.badRequest().body(Map.of("error",
							"En la solapa SQL sÃ³lo se permite SELECT (DESC sirve para ver tablas y columnas).",
							"statement",hql.trim()));
				}
				return ResponseEntity.ok(runner.executeSqlReadOnly(statement));
			}
			catch(Exception e)
			{
				return ResponseEntity.badRequest().body(_error(e,hql));
			}
		}

		// allow-writes se mira ANTES de cualquier cosa: con la consola en solo-lectura, un dry-run
		// tampoco ejecuta (no tiene sentido correr y tirar atrás una escritura prohibida).
		if( _hasWrite(statements)&&!properties.isAllowWrites() )
		{
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(Map.of("error","Las sentencias de escritura están bloqueadas (hql-console.allow-writes=false).",
							     "statement",hql.trim()));
		}

		// El dry-run sólo tiene sentido en UPDATE y DELETE (en un INSERT no hay nada que confirmar) y
		// en una sentencia sola: un lote es siempre de INSERT.
		boolean dryRun=Boolean.TRUE.equals(body.get("dryRun"));

		try
		{
			// Con una sola sentencia se usa el texto ya recortado (sin el ';' del final); con más de
			// una, el lote.
			return ResponseEntity.ok(statements.size()==1
					?runner.execute(statements.get(0),dryRun)
					:runner.executeBatch(statements));
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
		out.put("stacktrace",_stacktrace(e));
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

	private boolean _hasWrite(List<String> statements)
	{
		for(String statement:statements)
		{
			String first=_firstWord(statement);
			if( "insert".equalsIgnoreCase(first)||"update".equalsIgnoreCase(first)||"delete".equalsIgnoreCase(first) )
			{
				return true;
			}
			if( BatchPlan.isGeneratedIdDeclaration(statement) )
			{
				return true;
			}
		}
		return false;
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
