package thejavalistener.hqlconsole.engine;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Convención opcional para dar un texto breve a una entidad relacionada en la consola.
 *
 * <p>No hay anotación ni dependencia de la consola en el dominio: basta declarar un método
 * público de instancia {@code String toHqlConsoleString()} sin argumentos. Si el método no existe,
 * devuelve un valor inútil o falla, la consola simplemente no muestra una etiqueta.</p>
 */
final class HqlConsoleLabel
{
	static final int MAX_LENGTH=60;

	private HqlConsoleLabel() {}

	static boolean supports(Class<?> type)
	{
		return _method(type)!=null;
	}

	/** Devuelve una etiqueta normalizada o {@code null} cuando la convención no aplica. */
	static String read(Object value)
	{
		if( value==null )
		{
			return null;
		}
		Method method=_method(value.getClass());
		if( method==null )
		{
			return null;
		}
		try
		{
			Object result=method.invoke(value);
			return result instanceof String text?_normalize(text):null;
		}
		catch(ReflectiveOperationException|RuntimeException unavailable)
		{
			return null;
		}
	}

	private static Method _method(Class<?> type)
	{
		try
		{
			Method method=type.getMethod("toHqlConsoleString");
			return method.getReturnType()==String.class&&!Modifier.isStatic(method.getModifiers())?method:null;
		}
		catch(NoSuchMethodException|SecurityException unavailable)
		{
			return null;
		}
	}

	private static String _normalize(String text)
	{
		String normalized=text.replaceAll("\\s+"," ").trim();
		if( normalized.isEmpty() )
		{
			return null;
		}
		if( normalized.codePointCount(0,normalized.length())<=MAX_LENGTH )
		{
			return normalized;
		}
		return normalized.substring(0,normalized.offsetByCodePoints(0,MAX_LENGTH-1))+"…";
	}
}
