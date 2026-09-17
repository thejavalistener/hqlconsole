package thejavalistener.hqlconsole.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Escaneo de texto de las sentencias.
 *
 * <p>Todo lo de acá entiende de paréntesis y de literales entre comillas simples, que es lo mínimo
 * indispensable para no confundir la coma de una función con la coma que separa dos asignaciones,
 * ni un {@code from} de una subconsulta con el {@code from} de la consulta de afuera.</p>
 */
public final class Text
{
	private Text() {}

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
