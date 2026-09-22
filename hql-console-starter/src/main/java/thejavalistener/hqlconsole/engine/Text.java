package thejavalistener.hqlconsole.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Escaneo de texto de las sentencias.
 *
 * <p>Todo lo de acá entiende de paréntesis, de literales entre comillas simples y de <b>comentarios</b>,
 * que es lo mínimo indispensable para no confundir la coma de una función con la coma que separa dos
 * asignaciones, ni un {@code from} de una subconsulta con el {@code from} de la consulta de afuera, ni
 * un {@code where} que está adentro de un comentario con el {@code WHERE} de la sentencia.</p>
 *
 * <p>Los comentarios son los de siempre: {@code //} hasta el fin de línea, {@code #} hasta el fin de
 * línea, y {@code --} hasta el fin de línea. No hay comentarios de bloque (los de {@code barra
 * asterisco}) y no hace falta: con los de línea alcanza y son los que la consola documenta.</p>
 */
public final class Text
{
	private Text() {}

	/**
	 * El largo de un comentario que arranca en {@code i}, o 0 si ahí no empieza ninguno.
	 *
	 * <p>Los tres terminan en el fin de línea, así que el comentario se come también el salto (que es
	 * lo que hace {@link #withoutComments} al reemplazarlo por un espacio).</p>
	 */
	private static int _commentLength(String text,int i)
	{
		char c=text.charAt(i);
		char next=i+1<text.length()?text.charAt(i+1):'\0';

		boolean arranca=(c=='/'&&next=='/')||(c=='-'&&next=='-')||c=='#';
		if( !arranca )
		{
			return 0;
		}
		int j=i;
		while( j<text.length()&&text.charAt(j)!='\n' )
		{
			j++;
		}
		return j-i;
	}

	/** Primera palabra de la sentencia, sin espacios adelante. */
	public static String firstWord(String text)
	{
		int i=0;
		while( i<text.length()&&Character.isWhitespace(text.charAt(i)) )
		{
			i++;
		}
		int start=i;
		while( i<text.length()&&!Character.isWhitespace(text.charAt(i)) )
		{
			i++;
		}
		return text.substring(start,i);
	}

	/** ¿El texto arranca con esa palabra completa? (evita confundir "selection" con "select") */
	public static boolean startsWithWord(String text,String word)
	{
		return wordAt(text,0,word);
	}

	/** ¿La palabra aparece en {@code at} y no está pegada a más caracteres de identificador? */
	public static boolean wordAt(String text,int at,String word)
	{
		if( at<0||at+word.length()>text.length()||!text.regionMatches(true,at,word,0,word.length()) )
		{
			return false;
		}
		int before=at-1;
		if( before>=0&&_isIdentifierChar(text.charAt(before)) )
		{
			return false;
		}
		int after=at+word.length();
		return after>=text.length()||!_isIdentifierChar(text.charAt(after));
	}

	/**
	 * Índice de la palabra clave a nivel 0: fuera de paréntesis y fuera de literales. Devuelve -1
	 * si no aparece.
	 */
	public static int indexOfKeyword(String text,String keyword)
	{
		return indexOfKeyword(text,keyword,0);
	}

	public static int indexOfKeyword(String text,String keyword,int from)
	{
		int depth=0;
		boolean inString=false;
		for(int i=Math.max(from,0);i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
				continue;
			}
			int comentario=_commentLength(text,i);
			if( comentario>0 )
			{
				i+=comentario-1; // el "where" que está adentro de un comentario no es el WHERE
				continue;
			}
			if( c=='(' )
			{
				depth++;
				continue;
			}
			if( c==')' )
			{
				depth--;
				continue;
			}
			if( depth==0&&(Character.toLowerCase(c)==Character.toLowerCase(keyword.charAt(0)))
					&&wordAt(text,i,keyword) )
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * Índice de un carácter a nivel 0 (fuera de paréntesis y de literales). Para el "=" de una
	 * asignación: ignora los que forman parte de {@code !=}, {@code <=}, {@code >=} o {@code ==}.
	 */
	public static int indexOfTopLevel(String text,char target)
	{
		int depth=0;
		boolean inString=false;
		for(int i=0;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
				continue;
			}
			int comentario=_commentLength(text,i);
			if( comentario>0 )
			{
				i+=comentario-1;
				continue;
			}
			if( c=='(' )
			{
				depth++;
				continue;
			}
			if( c==')' )
			{
				depth--;
				continue;
			}
			if( depth!=0||c!=target )
			{
				continue;
			}
			if( i>0&&(text.charAt(i-1)=='!'||text.charAt(i-1)=='<'||text.charAt(i-1)=='>') )
			{
				continue;
			}
			if( i+1<text.length()&&text.charAt(i+1)==target )
			{
				continue;
			}
			return i;
		}
		return -1;
	}

	/**
	 * Índice del paréntesis que cierra al que está en {@code openAt}, o -1 si no cierra nunca.
	 * Entiende los paréntesis anidados y no se confunde con los que están dentro de un literal.
	 */
	public static int matchParenthesis(String text,int openAt)
	{
		if( openAt<0||openAt>=text.length()||text.charAt(openAt)!='(' )
		{
			return -1;
		}
		int depth=0;
		boolean inString=false;
		for(int i=openAt;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
			}
			else if( _commentLength(text,i)>0 )
			{
				i+=_commentLength(text,i)-1; // un paréntesis adentro de un comentario no cuenta
			}
			else if( c=='(' )
			{
				depth++;
			}
			else if( c==')' )
			{
				depth--;
				if( depth==0 )
				{
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Índice del último paréntesis que queda sin cerrar, o -1 si todos cierran. Sirve para distinguir
	 * una sentencia de la consola mal escrita de una que directamente no es de la consola: si los
	 * paréntesis no cierran, no puede ser HQL válido tampoco.
	 */
	public static int unclosedParenthesis(String text)
	{
		int depth=0;
		int abierto=-1;
		boolean inString=false;
		for(int i=0;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
			}
			else if( _commentLength(text,i)>0 )
			{
				i+=_commentLength(text,i)-1;
			}
			else if( c=='(' )
			{
				depth++;
				abierto=i;
			}
			else if( c==')' )
			{
				depth--;
				if( depth<=0 )
				{
					depth=0;
					abierto=-1;
				}
			}
		}
		return depth>0?abierto:-1;
	}

	/** Separa por comas de primer nivel, respetando paréntesis y literales. */
	public static List<String> splitTopLevel(String text)
	{
		List<String> parts=new ArrayList<>();
		int depth=0;
		boolean inString=false;
		int start=0;
		for(int i=0;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
			}
			else if( _commentLength(text,i)>0 )
			{
				i+=_commentLength(text,i)-1; // una coma adentro de un comentario no separa nada
			}
			else if( c=='(' )
			{
				depth++;
			}
			else if( c==')' )
			{
				depth--;
			}
			else if( c==','&&depth==0 )
			{
				parts.add(text.substring(start,i));
				start=i+1;
			}
		}
		parts.add(text.substring(start));
		return parts;
	}

	/**
	 * Parte el texto en sentencias por punto y coma de primer nivel, sin las vacías.
	 *
	 * <p>Un punto y coma dentro de un literal no parte nada ({@code 'a;b'} es un solo valor), y se
	 * toleran los punto y coma de más: el del final y los de {@code ;;} simplemente no son una
	 * sentencia. El corte va acá, antes de parsear, para que cualquier formato de sentencia funcione
	 * igual adentro de un lote.</p>
	 */
	public static List<String> splitStatements(String text)
	{
		List<String> partes=new ArrayList<>();
		int depth=0;
		boolean inString=false;
		int start=0;
		for(int i=0;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
			}
			else if( _commentLength(text,i)>0 )
			{
				// Un punto y coma adentro de un comentario NO parte la sentencia: es el bug que este
				// salto arregla (antes, "-- hacé esto; y aquello" cortaba la sentencia al medio).
				i+=_commentLength(text,i)-1;
			}
			else if( c=='(' )
			{
				depth++;
			}
			else if( c==')' )
			{
				depth--;
			}
			else if( c==';'&&depth==0 )
			{
				partes.add(text.substring(start,i));
				start=i+1;
			}
		}
		partes.add(text.substring(start));

		List<String> sentencias=new ArrayList<>();
		for(String parte:partes)
		{
			String limpia=parte.trim();
			if( !limpia.isEmpty() )
			{
				sentencias.add(limpia);
			}
		}
		return sentencias;
	}

	/**
	 * La sentencia sin los comentarios, que es lo que se le manda a Hibernate.
	 *
	 * <p>Cada comentario se reemplaza por <b>un espacio</b>, no se borra. Es la diferencia entre
	 * {@code titulo-- el titulo\n, precio} (que sin el espacio quedaría {@code titulo, precio}... o
	 * peor, {@code titulo, precio} pegado a otra cosa) y lo que corresponde: al sacar caracteres, dos
	 * tokens que estaban separados por un comentario terminarían pegados y la sentencia cambiaría de
	 * significado. Con un espacio, el peor caso es un espacio de más.</p>
	 *
	 * <p>Un comentario adentro de un literal no es un comentario: {@code 'a--b'} es un texto y se
	 * respeta tal cual.</p>
	 */
	public static String withoutComments(String text)
	{
		StringBuilder salida=new StringBuilder(text.length());
		boolean inString=false;
		for(int i=0;i<text.length();i++)
		{
			char c=text.charAt(i);
			if( inString )
			{
				salida.append(c);
				if( c=='\'' )
				{
					inString=false;
				}
				continue;
			}
			if( c=='\'' )
			{
				inString=true;
				salida.append(c);
				continue;
			}
			int comentario=_commentLength(text,i);
			if( comentario>0 )
			{
				salida.append(' ');
				i+=comentario-1; // el salto de línea final entra en el comentario y se descarta
				continue;
			}
			salida.append(c);
		}
		return salida.toString();
	}

	/** Identificador a partir de {@code at}, admitiendo puntos (nombres de clase y de atributo). */
	public static String identifierAt(String text,int at)
	{
		int i=at;
		while( i<text.length()&&Character.isWhitespace(text.charAt(i)) )
		{
			i++;
		}
		int start=i;
		while( i<text.length()&&(_isIdentifierChar(text.charAt(i))) )
		{
			i++;
		}
		return i>start?text.substring(start,i):null;
	}

	public static boolean isQuoted(String text)
	{
		return text.length()>=2&&text.charAt(0)=='\''&&text.charAt(text.length()-1)=='\'';
	}

	/** Saca las comillas y resuelve el escape {@code ''} de SQL. */
	public static String unquote(String text)
	{
		return isQuoted(text)?text.substring(1,text.length()-1).replace("''","'"):text;
	}

	private static boolean _isIdentifierChar(char c)
	{
		return Character.isLetterOrDigit(c)||c=='_'||c=='$'||c=='.';
	}
}
