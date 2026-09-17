package thejavalistener.hqlconsole.engine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;

/**
 * Traduce {@code li.campo=valor} a un valor del tipo correcto y lo asigna a la entidad.
 *
 * <p>Dos responsabilidades que van juntas porque una no sirve sin la otra: <b>resolver</b> la ruta
 * contra el metamodelo (con lo que se sabe el tipo Java destino) y <b>convertir</b> el literal a ese
 * tipo. El {@code NOW} es el caso que obliga a que estén juntas: la misma palabra tiene que dar un
 * {@code DATE} o un {@code TIMESTAMP} según el campo al que se asigne.</p>
 *
 * <p>La asignación prefiere el setter y cae al campo. El setter primero no es capricho: en una
 * entidad cargada como proxy de Hibernate, tocar el campo por reflexión escribe en el proxy y se
 * pierde cuando el proxy se inicializa; el setter fuerza la inicialización.</p>
 */
public class AttributeBinder
{
	private final EntityManager em;

	public AttributeBinder(EntityManager em)
	{
		this.em=em;
	}

	/** Destino de una asignación: el atributo, el tipo al que convertir y, si es una relación, qué entidad referencia. */
	public record Target(Attribute<?,?> attribute,String path,Class<?> javaType,Class<?> relatedEntity) {}

	// ==================== resolución de la ruta ====================

	public Target resolve(EntityType<?> entityType,String alias,String path)
	{
		String[] segments=path.split("\\.");
		int start=0;
		if( segments.length>1&&alias!=null&&!segments[0].equals(alias)&&segments[0].equalsIgnoreCase(alias) )
		{
			throw new IllegalArgumentException("El alias es '"+alias+"' y lo escribiste '"+segments[0]
					+"': las mayúsculas importan");
		}
		if( segments.length>1&&_isQualifier(segments[0],alias,entityType) )
		{
			start=1;
		}
		if( start>=segments.length||segments[start].isEmpty() )
		{
			throw new IllegalArgumentException("La ruta '"+path+"' no dice qué campo tocar");
		}

		Attribute<?,?> attribute=_attributeOf(entityType,segments[start]);
		if( attribute instanceof PluralAttribute )
		{
			throw new IllegalArgumentException("'"+path+"' es una colección: no se puede asignar una colección desde una sentencia");
		}

		int rest=segments.length-start-1;
		if( rest==0 )
		{
			// "li.autor=5": el literal es el id del Autor, no un Autor.
			if( Mapping.isToOne(attribute) )
			{
				EntityType<?> related=_relatedEntityType(attribute);
				return new Target(attribute,path,related.getIdType().getJavaType(),related.getJavaType());
			}
			return new Target(attribute,path,attribute.getJavaType(),null);
		}

		// "li.autor.id" es la FK, o sea el mismo destino que "li.autor".
		if( rest==1&&Mapping.isToOne(attribute) )
		{
			EntityType<?> related=_relatedEntityType(attribute);
			Attribute<?,?> id=Mapping.idOf(related);
			if( segments[start+1].equals(id.getName()) )
			{
				return new Target(attribute,path,related.getIdType().getJavaType(),related.getJavaType());
			}
		}

		throw new IllegalArgumentException("No entiendo la ruta '"+path+"': se puede asignar un campo de "
				+entityType.getName()+" o el id de una relación suya (por ejemplo 'li."+_someRelation(entityType)+"')");
	}

	/** El prefijo de una ruta es el alias, o el nombre de la entidad si no se puso alias. Exacto: las mayúsculas importan. */
	private boolean _isQualifier(String segment,String alias,EntityType<?> entityType)
	{
		if( alias!=null&&segment.equals(alias) )
		{
			return true;
		}
		return segment.equals(entityType.getName())
				||segment.equals(entityType.getJavaType().getSimpleName());
	}

	private Attribute<?,?> _attributeOf(EntityType<?> entityType,String name)
	{
		for(Attribute<?,?> attribute:entityType.getAttributes())
		{
			if( attribute.getName().equals(name) )
			{
				return attribute;
			}
		}

		// Los atributos son case sensitive, como en HQL. Si lo único que falló fue el case,
		// conviene decirlo en vez de dejar al usuario buscando el error.
		String suggestion=null;
		for(Attribute<?,?> attribute:entityType.getAttributes())
		{
			if( attribute.getName().equalsIgnoreCase(name) )
			{
				suggestion=attribute.getName();
				break;
			}
		}
		throw new IllegalArgumentException("'"+entityType.getName()+"' no tiene el campo '"+name+"'."
				+(suggestion==null?"":" ¿Quisiste decir '"+suggestion+"'?")
				+" Tiene: "+_attributeNames(entityType));
	}

	private String _attributeNames(EntityType<?> entityType)
	{
		StringBuilder builder=new StringBuilder();
		for(Attribute<?,?> attribute:entityType.getAttributes())
		{
			if( builder.length()>0 )
			{
				builder.append(", ");
			}
			builder.append(attribute.getName());
		}
		return builder.toString();
	}

	private String _someRelation(EntityType<?> entityType)
	{
		for(Attribute<?,?> attribute:entityType.getAttributes())
		{
			if( Mapping.isToOne(attribute) )
			{
				return attribute.getName();
			}
		}
		return "id";
	}

	// ==================== conversión del literal ====================

	/**
	 * Convierte el literal al valor que espera el atributo. Si el destino es una relación, el
	 * literal es el <b>id</b> de la entidad referenciada y se devuelve una referencia a ella.
	 */
	public Object value(Target target,String literal)
	{
		String typeLabel=target.relatedEntity()!=null
				?"id de "+target.relatedEntity().getSimpleName()
				:target.javaType().getSimpleName();
		try
		{
			Object converted=_convert(literal,target.javaType());
			if( target.relatedEntity()!=null )
			{
				return converted==null?null:em.getReference(target.relatedEntity(),converted);
			}
			return converted;
		}
		catch(RuntimeException e)
		{
			throw new IllegalArgumentException("No pude asignar "+literal+" a '"+target.path()+"' ("+typeLabel+"): "+e.getMessage(),e);
		}
	}

	private Object _convert(String literal,Class<?> javaType)
	{
		if( "null".equalsIgnoreCase(literal.trim()) )
		{
			if( javaType.isPrimitive() )
			{
				throw new IllegalArgumentException("el campo es primitivo y no acepta NULL");
			}
			return null;
		}
		if( _isNow(literal) )
		{
			return _now(javaType);
		}

		String text=Text.isQuoted(literal)?Text.unquote(literal):literal;

		if( javaType==String.class||javaType==Object.class )
		{
			return text;
		}
		if( javaType==Integer.class||javaType==int.class )
		{
			return Integer.valueOf(_integerText(text));
		}
		if( javaType==Long.class||javaType==long.class )
		{
			return Long.valueOf(_integerText(text));
		}
		if( javaType==Short.class||javaType==short.class )
		{
			return Short.valueOf(_integerText(text));
		}
		if( javaType==Byte.class||javaType==byte.class )
		{
			return Byte.valueOf(_integerText(text));
		}
		if( javaType==BigInteger.class )
		{
			return new BigInteger(_integerText(text));
		}
		if( javaType==BigDecimal.class )
		{
			return new BigDecimal(text.trim());
		}
		if( javaType==Double.class||javaType==double.class )
		{
			return Double.valueOf(text.trim());
		}
		if( javaType==Float.class||javaType==float.class )
		{
			return Float.valueOf(text.trim());
		}
		if( javaType==Boolean.class||javaType==boolean.class )
		{
			return _boolean(text);
		}
		if( javaType==Character.class||javaType==char.class )
		{
			if( text.isEmpty() )
			{
				throw new IllegalArgumentException("un carácter no puede ser vacío");
			}
			return text.charAt(0);
		}
		if( javaType.isEnum() )
		{
			return _enumConstant(javaType,text);
		}
		if( javaType==UUID.class )
		{
			return UUID.fromString(text.trim());
		}
		if( javaType==LocalDate.class )
		{
			return LocalDate.parse(_datePart(text));
		}
		if( javaType==LocalDateTime.class )
		{
			return LocalDateTime.parse(_dateTimeText(text));
		}
		if( javaType==LocalTime.class )
		{
			return LocalTime.parse(text.trim());
		}
		if( javaType==Instant.class )
		{
			return _instant(text);
		}
		if( javaType==OffsetDateTime.class )
		{
			return OffsetDateTime.parse(_dateTimeText(text));
		}
		if( javaType==ZonedDateTime.class )
		{
			return ZonedDateTime.parse(_dateTimeText(text));
		}
		if( javaType==java.util.Date.class )
		{
			return java.util.Date.from(_instant(text));
		}
		if( javaType==java.sql.Timestamp.class )
		{
			return java.sql.Timestamp.valueOf(LocalDateTime.parse(_dateTimeText(text)));
		}
		if( javaType==java.sql.Date.class )
		{
			return java.sql.Date.valueOf(LocalDate.parse(_datePart(text)));
		}
		if( javaType==java.sql.Time.class )
		{
			return java.sql.Time.valueOf(LocalTime.parse(text.trim()));
		}
		if( javaType==byte[].class )
		{
			return Base64.getDecoder().decode(text.trim());
		}

		throw new IllegalArgumentException("no sé convertir un literal a "+javaType.getName());
	}

	private boolean _isNow(String literal)
	{
		String text=literal.trim().toLowerCase(Locale.ROOT);
		if( text.endsWith("()") )
		{
			text=text.substring(0,text.length()-2).trim();
		}
		return text.equals("now")||text.equals("current_timestamp")||text.equals("current_date")||text.equals("current_time");
	}

	/** El mismo NOW tiene que salir con el tipo exacto que espera el campo destino. */
	private Object _now(Class<?> javaType)
	{
		if( javaType==LocalDate.class )
		{
			return LocalDate.now();
		}
		if( javaType==java.sql.Date.class )
		{
			return java.sql.Date.valueOf(LocalDate.now());
		}
		if( javaType==LocalDateTime.class )
		{
			return LocalDateTime.now();
		}
		if( javaType==java.sql.Timestamp.class )
		{
			return java.sql.Timestamp.valueOf(LocalDateTime.now());
		}
		if( javaType==LocalTime.class )
		{
			return LocalTime.now();
		}
		if( javaType==java.sql.Time.class )
		{
			return java.sql.Time.valueOf(LocalTime.now());
		}
		if( javaType==Instant.class )
		{
			return Instant.now();
		}
		if( javaType==OffsetDateTime.class )
		{
			return OffsetDateTime.now();
		}
		if( javaType==ZonedDateTime.class )
		{
			return ZonedDateTime.now();
		}
		if( javaType==java.util.Date.class )
		{
			return new java.util.Date();
		}
		if( javaType==String.class )
		{
			return LocalDateTime.now().toString();
		}
		throw new IllegalArgumentException("NOW no aplica a "+javaType.getSimpleName());
	}

	private String _integerText(String text)
	{
		String value=text.trim();
		// Tolera el "132.0" que sale de pegar un decimal donde va un entero.
		if( value.endsWith(".0") )
		{
			value=value.substring(0,value.length()-2);
		}
		return value;
	}

	private Boolean _boolean(String text)
	{
		String value=text.trim().toLowerCase(Locale.ROOT);
		if( value.equals("true")||value.equals("1")||value.equals("si")||value.equals("yes") )
		{
			return Boolean.TRUE;
		}
		if( value.equals("false")||value.equals("0")||value.equals("no") )
		{
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException("'"+text+"' no es un booleano (true/false)");
	}

	private Object _enumConstant(Class<?> javaType,String text)
	{
		StringBuilder valid=new StringBuilder();
		for(Object constant:javaType.getEnumConstants())
		{
			String name=((Enum<?>)constant).name();
			if( name.equalsIgnoreCase(text.trim()) )
			{
				return constant;
			}
			if( valid.length()>0 )
			{
				valid.append(", ");
			}
			valid.append(name);
		}
		throw new IllegalArgumentException("'"+text+"' no es un valor de "+javaType.getSimpleName()+" (esperaba: "+valid+")");
	}

	/** Acepta "1994-11-23" o "1994-11-23 10:30" además del ISO con T. */
	private String _dateTimeText(String text)
	{
		String value=text.trim();
		if( value.length()>=11&&value.charAt(10)==' ' )
		{
			return value.substring(0,10)+"T"+value.substring(11);
		}
		if( value.length()==10 )
		{
			return value+"T00:00";
		}
		return value;
	}

	private String _datePart(String text)
	{
		String value=text.trim();
		return value.length()>10?value.substring(0,10):value;
	}

	private Instant _instant(String text)
	{
		String value=text.trim();
		try
		{
			return Instant.parse(value);
		}
		catch(DateTimeParseException notAnInstant)
		{
			return LocalDateTime.parse(_dateTimeText(value)).atZone(ZoneId.systemDefault()).toInstant();
		}
	}

	// ==================== asignación ====================

	public void apply(Object entity,Target target,Object value)
	{
		String name=target.attribute().getName();

		Method setter=_setter(entity.getClass(),name,value);
		if( setter!=null )
		{
			try
			{
				setter.invoke(entity,value);
				return;
			}
			catch(ReflectiveOperationException e)
			{
				throw new IllegalArgumentException("No pude invocar "+setter.getName()+" en "
						+entity.getClass().getSimpleName()+": "+e.getMessage(),e);
			}
		}

		Field field=Mapping.field(entity.getClass(),name);
		if( field==null )
		{
			throw new IllegalArgumentException("El campo '"+name+"' no tiene setter público ni atributo accesible en "
					+entity.getClass().getSimpleName());
		}
		try
		{
			field.set(entity,value);
		}
		catch(ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("No pude asignar el campo '"+name+"': "+e.getMessage(),e);
		}
	}

	private Method _setter(Class<?> type,String name,Object value)
	{
		String setterName="set"+Character.toUpperCase(name.charAt(0))+name.substring(1);
		Method fallback=null;
		for(Method method:type.getMethods())
		{
			if( !method.getName().equals(setterName)||method.getParameterCount()!=1 )
			{
				continue;
			}
			if( value==null||_accepts(method.getParameterTypes()[0],value.getClass()) )
			{
				return method;
			}
			if( fallback==null )
			{
				fallback=method;
			}
		}
		return fallback;
	}

	private boolean _accepts(Class<?> parameter,Class<?> argument)
	{
		if( parameter.isAssignableFrom(argument) )
		{
			return true;
		}
		Class<?> boxed=_boxed(parameter);
		return boxed!=null&&boxed==argument;
	}

	private Class<?> _boxed(Class<?> primitive)
	{
		if( primitive==int.class )
		{
			return Integer.class;
		}
		if( primitive==long.class )
		{
			return Long.class;
		}
		if( primitive==short.class )
		{
			return Short.class;
		}
		if( primitive==byte.class )
		{
			return Byte.class;
		}
		if( primitive==double.class )
		{
			return Double.class;
		}
		if( primitive==float.class )
		{
			return Float.class;
		}
		if( primitive==boolean.class )
		{
			return Boolean.class;
		}
		if( primitive==char.class )
		{
			return Character.class;
		}
		return null;
	}

	// ==================== helpers de metamodelo ====================

	private EntityType<?> _relatedEntityType(Attribute<?,?> attribute)
	{
		EntityType<?> related=Mapping.relatedEntity(em.getMetamodel(),attribute);
		if( related==null )
		{
			throw new IllegalArgumentException("La relación '"+attribute.getName()+"' apunta a "
					+attribute.getJavaType().getName()+", que no es una entidad del metamodelo");
		}
		return related;
	}
}
