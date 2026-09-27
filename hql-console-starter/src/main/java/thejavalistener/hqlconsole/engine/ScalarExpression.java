package thejavalistener.hqlconsole.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser mínimo de valores especiales de la consola; no interpreta HQL ni hace reemplazo textual. */
final class ScalarExpression
{
	private static final Object UNRESOLVED=new Object();
	private static final Pattern TEMPORAL=Pattern.compile("(?i)^(NOW(?:\\(\\))?|TODAY)(?:\\s*([+-])\\s*(\\d+))?$");
	private static final Pattern VARIABLE=Pattern.compile("^\\$([A-Za-z_][A-Za-z0-9_]*)(?:\\s*([+-])\\s*(\\d+))?$");

	private ScalarExpression() {}

	static Object resolve(String literal,ScriptContext context,Class<?> targetType)
	{
		String text=literal.trim();
		Matcher temporal=TEMPORAL.matcher(text);
		if( temporal.matches() )
		{
			long days=_days(temporal.group(2),temporal.group(3));
			return context.temporal("today".equalsIgnoreCase(temporal.group(1)),targetType,days);
		}

		Matcher variable=VARIABLE.matcher(text);
		if( variable.matches() )
		{
			Object value=context.variable(variable.group(1));
			if( variable.group(2)==null ) return value;
			long delta=_days(variable.group(2),variable.group(3));
			if( ScriptContext.isTemporal(value) ) return ScriptContext.plusDays(value,delta);
			if( value instanceof Number number ) return ScriptContext.plusNumber(number,delta);
			throw new IllegalArgumentException("La aritmética no aplica a $"+variable.group(1)
					+" ("+value.getClass().getSimpleName()+").");
		}

		if( text.regionMatches(true,0,"now",0,3)||text.regionMatches(true,0,"today",0,5)
				||text.startsWith("$") )
		{
			throw new IllegalArgumentException("Expresión escalar inválida: '"+literal
					+"'. Se acepta NOW/TODAY +/- días o $variable +/- entero.");
		}
		return UNRESOLVED;
	}

	static String referencedVariable(String literal)
	{
		Matcher matcher=VARIABLE.matcher(literal.trim());
		return matcher.matches()?matcher.group(1):null;
	}

	static boolean isResolved(Object value)
	{
		return value!=UNRESOLVED;
	}

	private static long _days(String operator,String digits)
	{
		if( operator==null ) return 0;
		try
		{
			long days=Long.parseLong(digits);
			return "+".equals(operator)?days:Math.negateExact(days);
		}
		catch(ArithmeticException|NumberFormatException e)
		{
			throw new IllegalArgumentException("La cantidad de días debe ser un entero válido: "+digits,e);
		}
	}
}
