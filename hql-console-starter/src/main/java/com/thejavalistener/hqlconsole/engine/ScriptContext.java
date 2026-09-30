package com.thejavalistener.hqlconsole.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Estado efímero de una ejecución de consola.
 *
 * <p>El contexto no pertenece al runner (que es singleton): se crea para una sentencia propia o
 * para un lote y se descarta al terminar. De esa manera las variables no se filtran a otra
 * ejecución y todas las expresiones temporales de un script comparten el mismo instante base.</p>
 */
final class ScriptContext
{
	private final Clock clock;
	private final Instant startedAt;
	private final Map<String,Object> scalars=new LinkedHashMap<>();

	ScriptContext()
	{
		this(Clock.systemDefaultZone());
	}

	ScriptContext(Clock clock)
	{
		this.clock=clock;
		this.startedAt=clock.instant();
	}

	void declare(String name,Object value)
	{
		if( value==null )
		{
			throw new IllegalArgumentException("La variable $"+name+" no puede guardar NULL.");
		}
		if( scalars.putIfAbsent(name,value)!=null )
		{
			throw new IllegalArgumentException("La variable $"+name+" ya fue declarada en este lote.");
		}
	}

	Object variable(String name)
	{
		Object value=scalars.get(name);
		if( value==null&&!scalars.containsKey(name) )
		{
			throw new IllegalArgumentException("La variable $"+name+" no está definida en este lote.");
		}
		return value;
	}

	Object temporal(boolean today,Class<?> javaType,long days)
	{
		ZonedDateTime reference=ZonedDateTime.ofInstant(startedAt,clock.getZone());
		LocalDate date=reference.toLocalDate().plusDays(days);
		if( javaType==LocalDate.class ) return date;
		if( javaType==java.sql.Date.class ) return java.sql.Date.valueOf(date);
		if( javaType==LocalDateTime.class ) return (today?date.atStartOfDay():reference.toLocalDateTime().plusDays(days));
		if( javaType==java.sql.Timestamp.class )
			return java.sql.Timestamp.valueOf(today?date.atStartOfDay():reference.toLocalDateTime().plusDays(days));
		if( javaType==Instant.class ) return (today?date.atStartOfDay(clock.getZone()).toInstant():startedAt.plusSeconds(days*86_400));
		if( javaType==OffsetDateTime.class ) return (today?date.atStartOfDay(clock.getZone()):reference.plusDays(days)).toOffsetDateTime();
		if( javaType==ZonedDateTime.class ) return today?date.atStartOfDay(clock.getZone()):reference.plusDays(days);
		if( javaType==Date.class ) return Date.from(today?date.atStartOfDay(clock.getZone()).toInstant():startedAt.plusSeconds(days*86_400));
		if( javaType==LocalTime.class||javaType==java.sql.Time.class )
		{
			if( today||days!=0 )
			{
				throw new IllegalArgumentException("La aritmética de días no aplica a "+javaType.getSimpleName()+" porque no contiene fecha.");
			}
			LocalTime time=reference.toLocalTime();
			return javaType==LocalTime.class?time:java.sql.Time.valueOf(time);
		}
		throw new IllegalArgumentException("NOW/TODAY sólo aplica a un campo temporal, no a "+javaType.getSimpleName()+".");
	}

	static boolean isTemporal(Object value)
	{
		return value instanceof LocalDate||value instanceof LocalDateTime||value instanceof LocalTime
				||value instanceof Instant||value instanceof OffsetDateTime||value instanceof ZonedDateTime
				||value instanceof Date||value instanceof java.sql.Time||value instanceof java.sql.Timestamp;
	}

	static Object plusDays(Object value,long days)
	{
		if( value instanceof LocalDate date ) return date.plusDays(days);
		if( value instanceof LocalDateTime dateTime ) return dateTime.plusDays(days);
		if( value instanceof Instant instant ) return instant.plusSeconds(days*86_400);
		if( value instanceof OffsetDateTime dateTime ) return dateTime.plusDays(days);
		if( value instanceof ZonedDateTime dateTime ) return dateTime.plusDays(days);
		if( value instanceof java.sql.Date date ) return java.sql.Date.valueOf(date.toLocalDate().plusDays(days));
		if( value instanceof java.sql.Timestamp timestamp ) return java.sql.Timestamp.valueOf(timestamp.toLocalDateTime().plusDays(days));
		if( value instanceof Date date ) return Date.from(date.toInstant().plusSeconds(days*86_400));
		throw new IllegalArgumentException("La aritmética de días no aplica a "+value.getClass().getSimpleName()+".");
	}

	static Object plusNumber(Number value,long delta)
	{
		try
		{
			if( value instanceof Byte v ) return Math.toIntExact(Math.addExact(v.longValue(),delta))>Byte.MAX_VALUE
					||Math.toIntExact(Math.addExact(v.longValue(),delta))<Byte.MIN_VALUE
						?overflow(value):Byte.valueOf((byte)(v.longValue()+delta));
			if( value instanceof Short v ) return Math.toIntExact(Math.addExact(v.longValue(),delta))>Short.MAX_VALUE
					||Math.toIntExact(Math.addExact(v.longValue(),delta))<Short.MIN_VALUE
						?overflow(value):Short.valueOf((short)(v.longValue()+delta));
			if( value instanceof Integer v ) return Math.toIntExact(Math.addExact(v.longValue(),delta));
			if( value instanceof Long v ) return Math.addExact(v,delta);
			if( value instanceof BigInteger v ) return v.add(BigInteger.valueOf(delta));
			if( value instanceof BigDecimal v ) return v.add(BigDecimal.valueOf(delta));
			if( value instanceof Float v ) return v+delta;
			if( value instanceof Double v ) return v+delta;
		}
		catch(ArithmeticException overflow)
		{
			throw new IllegalArgumentException("La aritmética de $variable desborda el tipo "+value.getClass().getSimpleName()+".",overflow);
		}
		throw new IllegalArgumentException("La aritmética no aplica a "+value.getClass().getSimpleName()+".");
	}

	private static Number overflow(Number value)
	{
		throw new IllegalArgumentException("La aritmética de $variable desborda el tipo "+value.getClass().getSimpleName()+".");
	}
}
