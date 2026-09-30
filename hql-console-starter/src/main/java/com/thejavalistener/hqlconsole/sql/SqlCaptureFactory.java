package com.thejavalistener.hqlconsole.sql;

import org.hibernate.SessionFactory;

import jakarta.persistence.EntityManagerFactory;

/** Selecciona un capturador sin obligar a la aplicación a incluir otros proveedores JPA. */
public final class SqlCaptureFactory
{
	private SqlCaptureFactory() { }

	public static SqlCapture createInstance(EntityManagerFactory emf)
	{
		String provider=_provider(emf);
		// Spring no está obligado a publicar jakarta.persistence.provider en el factory que expone
		// como bean. El unwrap es la comprobación efectiva y evita caer silenciosamente en NoOp.
		if( provider.contains("hibernate")||_isHibernate(emf) ) { return new HibernateSqlCapture(); }
		if( provider.contains("eclipselink")&&DynamicEclipseLinkCapture.isAvailable() )
		{
			return new DynamicEclipseLinkCapture();
		}
		if( provider.contains("openjpa")&&DynamicOpenJpaCapture.isAvailable() )
		{
			return new DynamicOpenJpaCapture();
		}
		return new NoOpSqlCapture();
	}

	private static String _provider(EntityManagerFactory emf)
	{
		Object provider=emf.getProperties().get("jakarta.persistence.provider");
		if( provider==null ) { provider=emf.getProperties().get("javax.persistence.provider"); }
		String name=provider==null?emf.getClass().getName():provider.toString();
		return name.toLowerCase(java.util.Locale.ROOT);
	}

	private static boolean _isHibernate(EntityManagerFactory emf)
	{
		try
		{
			emf.unwrap(SessionFactory.class);
			return true;
		}
		catch(RuntimeException notHibernate)
		{
			return false;
		}
	}
}
