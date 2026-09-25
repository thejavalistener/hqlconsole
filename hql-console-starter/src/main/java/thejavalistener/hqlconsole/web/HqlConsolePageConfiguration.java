package thejavalistener.hqlconsole.web;

/** Configuración serializada para la plantilla HTML, sin depender de un mapper JSON externo. */
final class HqlConsolePageConfiguration
{
	private HqlConsolePageConfiguration()
	{
	}

	static String json(String base,int maxRows)
	{
		return "{\"base\":"+_jsonString(base)+",\"maxRows\":"+maxRows+"}";
	}

	private static String _jsonString(String value)
	{
		String source=value==null?"":value;
		StringBuilder out=new StringBuilder(source.length()+16);
		out.append('"');
		for(int i=0;i<source.length();i++)
		{
			char character=source.charAt(i);
			switch(character)
			{
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '<' -> out.append("\\u003c");
				case '>' -> out.append("\\u003e");
				case '&' -> out.append("\\u0026");
				case '\u2028' -> out.append("\\u2028");
				case '\u2029' -> out.append("\\u2029");
				default ->
				{
					if( character<0x20 || Character.isSurrogate(character) )
					{
						out.append("\\u");
						String hex=Integer.toHexString(character);
						for(int pad=hex.length();pad<4;pad++) { out.append('0'); }
						out.append(hex);
					}
					else
					{
						out.append(character);
					}
				}
			}
		}
		return out.append('"').toString();
	}
}
