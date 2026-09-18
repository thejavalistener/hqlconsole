package thejavalistener.hqlconsole.engine;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;

/**
 * Lo que se puede saber del mapping sin preguntarle a la base: nombre de tabla, nombres de columna,
 * orden de declaración y lectura de valores por reflexión.
 *
 * <p>El metamodelo de JPA no expone ni nombres de tabla ni de columna, así que lo de acá es la
 * convención de Hibernate más lo que dicen las anotaciones. Cuando la base contesta, la metadata
 * real tiene prioridad (ver {@link EntityDescriber}).</p>
 */
public final class Mapping
{
	private Mapping() {}

	public static boolean isToOne(Attribute<?,?> attribute)
	{
		return attribute instanceof SingularAttribute<?,?> singular&&singular.isAssociation();
	}

	public static boolean isColumn(Attribute<?,?> attribute)
	{
		return !(attribute instanceof PluralAttribute);
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	public static Attribute<?,?> idOf(EntityType<?> entityType)
	{
		return ((EntityType)entityType).getId(entityType.getIdType().getJavaType());
	}

	public static EntityType<?> relatedEntity(Metamodel metamodel,Attribute<?,?> attribute)
	{
		try
		{
			return metamodel.entity(attribute.getJavaType());
		}
		catch(IllegalArgumentException notAnEntity)
		{
			return null;
		}
	}

	/** El nombre de la columna: lo que dice la anotación, o la convención de Hibernate. */
	public static String columnName(Metamodel metamodel,Attribute<?,?> attribute)
	{
		if( isToOne(attribute) )
		{
			JoinColumn join=annotation(attribute,JoinColumn.class);
			if( join!=null&&!join.name().isEmpty() )
			{
				return join.name();
			}
			EntityType<?> related=relatedEntity(metamodel,attribute);
			// Convención por defecto de Hibernate: <atributo>_<columna del id referenciado>.
			return snakeCase(attribute.getName())+"_"+(related==null?"id":idColumnName(related));
		}

		Column column=annotation(attribute,Column.class);
		if( column!=null&&!column.name().isEmpty() )
		{
			return column.name();
		}
		return snakeCase(attribute.getName());
	}

	public static String idColumnName(EntityType<?> entityType)
	{
		Attribute<?,?> id=idOf(entityType);
		Column column=annotation(id,Column.class);
		if( column!=null&&!column.name().isEmpty() )
		{
			return column.name();
		}
		return snakeCase(id.getName());
	}

	/** El nombre declarado en {@code @Table}, o la convención sobre el nombre de la entidad. */
	public static String tableName(EntityType<?> entityType)
	{
		Table table=entityType.getJavaType().getAnnotation(Table.class);
		if( table!=null&&!table.name().isEmpty() )
		{
			return table.name();
		}
		return snakeCase(entityType.getName());
	}

	/** Para una colección interesa el tipo del elemento, no "List". */
	public static String javaTypeName(Attribute<?,?> attribute)
	{
		if( attribute instanceof PluralAttribute<?,?,?> plural )
		{
			return plural.getElementType().getJavaType().getSimpleName();
		}
		return attribute.getJavaType().getSimpleName();
	}

	/**
	 * Los atributos que ocupan una columna en esta tabla (o sea, todo menos las colecciones),
	 * ordenados como están declarados en la clase.
	 *
	 * <p>El metamodelo los devuelve en un orden propio (alfabético, con el id primero) y la
	 * posición física de la columna tampoco sirve: Hibernate genera el DDL en el orden que quiere.
	 * El orden de declaración es el que uno reconoce al leer la entidad.</p>
	 */
	public static List<Attribute<?,?>> columnsInDeclarationOrder(EntityType<?> entityType)
	{
		List<Attribute<?,?>> attributes=new ArrayList<>();
		for(Attribute<?,?> attribute:entityType.getAttributes())
		{
			if( isColumn(attribute) )
			{
				attributes.add(attribute);
			}
		}
		List<String> order=declarationOrder(entityType.getJavaType());
		attributes.sort(Comparator.comparingInt(attribute -> {
			int index=order.indexOf(memberName(attribute));
			return index<0?Integer.MAX_VALUE:index;
		}));
		return attributes;
	}

	/**
	 * Nombres de los campos en orden de declaración, superclases primero (que es como se leen las
	 * entidades con {@code @MappedSuperclass}).
	 *
	 * <p>{@code getDeclaredFields()} no garantiza el orden por especificación, pero en la práctica
	 * (OpenJDK) devuelve el de declaración. Si algún día no lo hiciera, el peor caso es que las
	 * columnas salgan en el orden del metamodelo: es presentación.</p>
	 */
	public static List<String> declarationOrder(Class<?> type)
	{
		List<Class<?>> hierarchy=new ArrayList<>();
		for(Class<?> current=type;current!=null&&current!=Object.class;current=current.getSuperclass())
		{
			hierarchy.add(0,current);
		}

		List<String> names=new ArrayList<>();
		for(Class<?> current:hierarchy)
		{
			for(Field field:current.getDeclaredFields())
			{
				if( !Modifier.isStatic(field.getModifiers()) )
				{
					names.add(field.getName());
				}
			}
		}
		return names;
	}

	/** El nombre del campo detrás del atributo, sea de acceso por campo o por getter. */
	public static String memberName(Attribute<?,?> attribute)
	{
		Member member;
		try
		{
			member=attribute.getJavaMember();
		}
		catch(RuntimeException notAvailable)
		{
			return attribute.getName();
		}
		if( member instanceof Field field )
		{
			return field.getName();
		}
		if( member instanceof Method method )
		{
			String name=method.getName();
			if( name.startsWith("get")&&name.length()>3 )
			{
				return Character.toLowerCase(name.charAt(3))+name.substring(4);
			}
			if( name.startsWith("is")&&name.length()>2 )
			{
				return Character.toLowerCase(name.charAt(2))+name.substring(3);
			}
		}
		return attribute.getName();
	}

	public static <A extends Annotation> A annotation(Attribute<?,?> attribute,Class<A> type)
	{
		try
		{
			Member member=attribute.getJavaMember();
			if( member instanceof AnnotatedElement element )
			{
				return element.getAnnotation(type);
			}
		}
		catch(RuntimeException notAvailable)
		{
			return null;
		}
		return null;
	}

	/**
	 * Lee el valor de un atributo: getter primero, campo después.
	 *
	 * <p>En una relación perezosa el getter devuelve el proxy <b>sin inicializarlo</b> (Hibernate
	 * sólo inicializa cuando le mandás un mensaje al proxy), así que esto no dispara consultas.</p>
	 */
	public static Object read(Object entity,Attribute<?,?> attribute)
	{
		String name=attribute.getName();
		String capitalized=Character.toUpperCase(name.charAt(0))+name.substring(1);

		Method getter=_noArg(entity.getClass(),"get"+capitalized);
		if( getter==null )
		{
			Method booleanGetter=_noArg(entity.getClass(),"is"+capitalized);
			if( booleanGetter!=null&&(booleanGetter.getReturnType()==boolean.class||booleanGetter.getReturnType()==Boolean.class) )
			{
				getter=booleanGetter;
			}
		}

		try
		{
			if( getter!=null )
			{
				return getter.invoke(entity);
			}
			Field field=field(entity.getClass(),name);
			return field==null?null:field.get(entity);
		}
		catch(ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("No pude leer el campo '"+name+"' de "
					+entity.getClass().getSimpleName()+": "+e.getMessage(),e);
		}
	}

	private static Method _noArg(Class<?> type,String name)
	{
		for(Method method:type.getMethods())
		{
			if( method.getName().equals(name)&&method.getParameterCount()==0 )
			{
				return method;
			}
		}
		return null;
	}

	/** Sube por la jerarquía: el campo puede estar en una superclase. */
	public static Field field(Class<?> type,String name)
	{
		for(Class<?> current=type;current!=null&&current!=Object.class;current=current.getSuperclass())
		{
			try
			{
				Field field=current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			}
			catch(NoSuchFieldException notHere)
			{
				// seguimos con la superclase
			}
		}
		return null;
	}

	public static String snakeCase(String name)
	{
		StringBuilder out=new StringBuilder();
		for(int i=0;i<name.length();i++)
		{
			char c=name.charAt(i);
			if( Character.isUpperCase(c) )
			{
				boolean boundary=i>0&&(Character.isLowerCase(name.charAt(i-1))
						||(i+1<name.length()&&Character.isLowerCase(name.charAt(i+1))));
				if( boundary )
				{
					out.append('_');
				}
				out.append(Character.toLowerCase(c));
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	public static String upper(String value)
	{
		return value==null?null:value.toUpperCase(Locale.ROOT);
	}

	/**
	 * Un nombre físico (tabla o columna) como lo muestra la consola: en MAYÚSCULAS si viene en un
	 * solo caso, y tal cual si tiene mayúsculas mezcladas.
	 *
	 * <p>Es la convención para que la misma aplicación se vea igual en cualquier base —H2 guarda los
	 * identificadores sin comillas en mayúsculas y Postgres en minúsculas, así que sin esto la grilla
	 * de {@code DESC} cambia según dónde corra—. Los <b>atributos y las clases</b> no se tocan: se
	 * muestran como están escritos en el código.</p>
	 *
	 * <p>El corte en las mayúsculas mezcladas no es capricho: en SQL un identificador así sólo existe
	 * si está entrecomillado, y mayusculizarlo apuntaría a otro identificador. Un nombre en un solo
	 * caso, en cambio, se puede escribir sin comillas en cualquier base y resuelve igual. La metadata
	 * de JDBC no dice si el nombre estaba entrecomillado, pero las mayúsculas mezcladas lo delatan.</p>
	 */
	public static String physicalName(String name)
	{
		if( name==null )
		{
			return null;
		}
		String lower=name.toLowerCase(Locale.ROOT);
		return name.equals(lower)||name.equals(upper(name))?upper(name):name;
	}
}
