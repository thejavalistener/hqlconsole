package com.thejavalistener.jpqlconsole.sql;

import jakarta.persistence.EntityManagerFactory;

/** Punto de extensión sin dependencia de compilación para OpenJPA. */
public final class DynamicOpenJpaCapture implements SqlCapture
{
	private final NoOpSqlCapture fallback=new NoOpSqlCapture();

	public static boolean isAvailable()
	{
		return _present("org.apache.openjpa.persistence.OpenJPAEntityManagerFactorySPI");
	}

	@Override
	public void register(EntityManagerFactory emf)
	{
		// OpenJPA permite reemplazar su Log por configuración. Se resuelve por reflexión cuando
		// se incorpore el adapter de esa versión, sin introducir OpenJPA como dependencia.
		fallback.register(emf);
	}

	@Override
	public String getLastSql() { return fallback.getLastSql(); }

	private static boolean _present(String type)
	{
		try { Class.forName(type,false,DynamicOpenJpaCapture.class.getClassLoader()); return true; }
		catch(ClassNotFoundException|LinkageError absent) { return false; }
	}
}
