package com.thejavalistener.jpqlconsole.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser mínimo de valores especiales de la consola; no interpreta HQL ni hace reemplazo textual. */
final class ScalarExpression
{
	private static final Object UNRESOLVED=new Object();
	private static final Pattern TEMPORAL=Pattern.compile("(?i)^(NOW(?:\\(\\))?|TODAY)(?:\\s*([+-])\\s*(\\d+))?$");
	private static final Pattern VARIABLE=Pattern.compile("^\\$([A-Za-z_][A-Za-z0-9_]*)(?:\\s*([+-])\\s*(\\d+))?$");
	private static final Pattern DATE=Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
	private static final Pattern DATE_WITHOUT_PADDING=Pattern.compile("^\\d{4}-\\d{1,2}-\\d{1,2}$");
	private static final Pattern INTEGER=Pattern.compile("^[+-]?\\d+$");
	private static final Pattern DECIMAL=Pattern.compile("^[+-]?(?:\\d+\\.\\d*|\\.\\d+)$");

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

	/** Reconoce un literal que puede declarar una variable sin consultar la base. */
	static boolean isLiteral(String expression)
	{
		try
		{
			literalValue(expression);
			return true;
		}
		catch(NotALiteral ignored)
		{
			return false;
		}
	}

	/** Convierte un literal de declaración a su tipo Java real, sin inferirlo desde el destino. */
	static Object literalValue(String expression)
	{
		String text=expression.trim();
		if( text.regionMatches(true,0,"TEXT ",0,5) )
		{
			String quoted=text.substring(5).trim();
			if( !Text.isQuoted(quoted) ) throw new IllegalArgumentException("TEXT espera un valor entre comillas simples.");
			return Text.unquote(quoted);
		}
		if( Text.isQuoted(text) )
		{
			String value=Text.unquote(text);
			if( DATE.matcher(value).matches() )
			{
				try { return LocalDate.parse(value); }
				catch(DateTimeParseException e) { throw new IllegalArgumentException("La fecha '"+value+"' no es válida.",e); }
			}
			if( DATE_WITHOUT_PADDING.matcher(value).matches() )
			{
				throw new IllegalArgumentException("La fecha '"+value+"' debe usar el formato ISO yyyy-MM-dd.");
			}
			return value;
		}
		if( "true".equalsIgnoreCase(text)||"false".equalsIgnoreCase(text) ) return Boolean.valueOf(text);
		if( INTEGER.matcher(text).matches() ) return _integer(text);
		if( DECIMAL.matcher(text).matches() ) return new BigDecimal(text);
		if( "null".equalsIgnoreCase(text) ) throw new IllegalArgumentException("Una variable literal no puede ser NULL.");
		throw new NotALiteral();
	}

	private static Object _integer(String text)
	{
		try { return Integer.valueOf(text); }
		catch(NumberFormatException notAnInteger)
		{
			try { return Long.valueOf(text); }
			catch(NumberFormatException notALong) { return new BigInteger(text); }
		}
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

	/** Señal interna para distinguir “no es literal” de “es literal mal escrito”. */
	private static final class NotALiteral extends RuntimeException
	{
		private static final long serialVersionUID=1L;
	}
}
