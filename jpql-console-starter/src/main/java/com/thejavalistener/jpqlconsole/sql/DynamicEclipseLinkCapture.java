package com.thejavalistener.jpqlconsole.sql;

import jakarta.persistence.EntityManagerFactory;

/** Punto de extensión sin dependencia de compilación para EclipseLink. */
public final class DynamicEclipseLinkCapture implements SqlCapture
{
	private final NoOpSqlCapture fallback=new NoOpSqlCapture();

	public static boolean isAvailable()
	{
		return _present("org.eclipse.persistence.jpa.JpaEntityManagerFactory");
	}

	@Override
	public void register(EntityManagerFactory emf)
	{
		// EclipseLink expone su SessionLog mediante APIs no estables entre versiones. El adapter
		// queda aislado aquí para poder añadir ese hook por reflexión sin cargar sus clases.
		fallback.register(emf);
	}

	@Override
	public String getLastSql() { return fallback.getLastSql(); }

	private static boolean _present(String type)
	{
		try { Class.forName(type,false,DynamicEclipseLinkCapture.class.getClassLoader()); return true; }
		catch(ClassNotFoundException|LinkageError absent) { return false; }
	}
}
