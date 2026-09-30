package com.thejavalistener.hqlconsole.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valida la forma de un lote de escrituras antes de abrir una transacción.
 *
 * <p>{@code SET AUTOCOMMIT ON} no cambia una conexión ni queda guardado: es una directiva del
 * lote actual para que la interfaz no pida su confirmación única. Debe ser su primera sentencia
 * y siempre tiene que acompañar por lo menos una escritura.</p>
 */
public record BatchPlan(List<BatchPlan.Entry> entries, boolean autoCommit,Map<String,String> variables)
{
	private static final Pattern DECLARATION=Pattern.compile("(?s)^\\s*\\$([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");

	/** Una escritura del lote y, si corresponde, la variable que recibe su id generado. */
	/** Una entrada de script: INSERT puede generar ID; SELECT sólo puede declarar un escalar. */
	public record Entry(String statement,String generatedIdVariable,String scalarSelectVariable,String literalVariable)
	{
		public boolean isScalarSelect()
		{
			return scalarSelectVariable!=null;
		}

		public boolean isLiteralDeclaration()
		{
			return literalVariable!=null;
		}
	}

	/** Conserva la vista anterior del plan para los consumidores que sólo necesitan el texto. */
	public List<String> statements()
	{
		return entries.stream().map(Entry::statement).toList();
	}

	public static BatchPlan parse(List<String> input)
	{
		if( input==null||input.size()<2 )
		{
			throw new IllegalArgumentException("Un lote necesita al menos dos sentencias.");
		}

		boolean autoCommit=false;
		List<Entry> writes=new ArrayList<>();
		Map<String,String> variables=new LinkedHashMap<>();
		for(int i=0;i<input.size();i++)
		{
			String statement=input.get(i);
			if( isAutoCommitOn(statement) )
			{
				if( i!=0 )
				{
					throw new IllegalArgumentException("SET AUTOCOMMIT ON sólo puede ser la primera sentencia del lote.");
				}
				autoCommit=true;
				continue;
			}

			Matcher declaration=DECLARATION.matcher(statement);
			String variable=null;
			if( declaration.matches() )
			{
				variable=declaration.group(1);
				statement=declaration.group(2).trim();
				if( variables.containsKey(variable) )
				{
					throw new IllegalArgumentException("La variable $"+variable+" ya fue declarada en este lote.");
				}
				if( "select".equalsIgnoreCase(Text.firstWord(statement)) )
				{
					variables.put(variable,statement);
					writes.add(new Entry(statement,null,variable,null));
					continue;
				}
				if( ScalarExpression.isLiteral(statement) )
				{
					variables.put(variable,statement);
					writes.add(new Entry(statement,null,null,variable));
					continue;
				}
				Statement parsed=StatementParser.parse(statement);
				if( parsed==null||parsed.kind()!=Statement.Kind.INSERT )
				{
					throw new IllegalArgumentException("La variable $"+variable
							+" sÃ³lo puede declararse como '$nombre = INSERT ... VALUES (...)'.");
				}
				if( variables.containsKey(variable) )
				{
					throw new IllegalArgumentException("La variable $"+variable+" ya fue declarada en este lote.");
				}
				_validateReferences(parsed,variables);
				variables.put(variable,statement);
			}

			String first=Text.firstWord(statement);
			if( !"insert".equalsIgnoreCase(first)&&!"update".equalsIgnoreCase(first)
					&& !"delete".equalsIgnoreCase(first) )
			{
				throw new IllegalArgumentException("La sentencia "+(i+1)+" de "+input.size()
						+" no es INSERT, UPDATE ni DELETE (empieza con '"+first+"').");
			}
			if( variable==null )
			{
				Statement parsed=StatementParser.parse(statement);
				if( parsed!=null )
				{
					_validateReferences(parsed,variables);
				}
			}
			writes.add(new Entry(statement,variable,null,null));
		}
		if( writes.isEmpty() )
		{
			throw new IllegalArgumentException("SET AUTOCOMMIT ON sólo es válido dentro de un lote con escrituras.");
		}
		if( writes.stream().allMatch(entry -> entry.isScalarSelect()||entry.isLiteralDeclaration()) )
		{
			throw new IllegalArgumentException("Un script con SELECT escalares necesita por lo menos una escritura.");
		}
		return new BatchPlan(List.copyOf(writes),autoCommit,
				Collections.unmodifiableMap(new LinkedHashMap<>(variables)));
	}

	private static void _validateReferences(Statement statement,Map<String,String> variables)
	{
		for(Statement.Assignment assignment:statement.assignments())
		{
			String variable=ScalarExpression.referencedVariable(assignment.literal());
			if( variable!=null&&!variables.containsKey(variable) )
			{
				throw new IllegalArgumentException("La variable $"+variable
						+" no estÃ¡ definida antes de esta sentencia del lote.");
			}
		}
	}

	public static boolean isGeneratedIdDeclaration(String statement)
	{
		if( statement==null ) return false;
		Matcher matcher=DECLARATION.matcher(statement);
		return matcher.matches()&&"insert".equalsIgnoreCase(Text.firstWord(matcher.group(2)));
	}

	public static boolean isVariableDeclaration(String statement)
	{
		return statement!=null&&DECLARATION.matcher(statement).matches();
	}

	public static boolean isAutoCommitOn(String statement)
	{
		return statement!=null&&statement.trim().matches("(?i)set\\s+autocommit\\s+on");
	}
}
