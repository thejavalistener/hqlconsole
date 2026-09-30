package com.thejavalistener.jpqlconsole.sql;

import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Captura SQL de Hibernate mediante {@link StatementInspector}.
 *
 * <p>El inspector se instala en las sesiones abiertas por la consola, no en el factory global.
 * Hibernate fija el inspector global al construir el factory; intentar cambiarlo después por
 * reflexión sería frágil e interferiría con la aplicación anfitriona.</p>
 */
public final class HibernateSqlCapture implements SqlCapture, StatementInspector
{
	private final AtomicReference<String> lastSql=new AtomicReference<>();
	private volatile SessionFactory sessionFactory;

	@Override
	public void register(EntityManagerFactory emf)
	{
		try
		{
			sessionFactory=emf.unwrap(SessionFactory.class);
		}
		catch(RuntimeException incompatible)
		{
			sessionFactory=null;
		}
	}

	/** Abre una sesión de Hibernate con este inspector, conservando el EMF de la aplicación intacto. */
	public EntityManager createEntityManager(EntityManagerFactory emf)
	{
		SessionFactory factory=sessionFactory;
		if( factory==null )
		{
			register(emf);
			factory=sessionFactory;
		}
		return factory==null?emf.createEntityManager():factory.withOptions().statementInspector(this).openSession();
	}

	/** Inicia una nueva ejecución sin reutilizar el SQL de la sentencia anterior. */
	public void clear()
	{
		lastSql.set(null);
	}

	@Override
	public String inspect(String sql)
	{
		lastSql.set(sql);
		return sql;
	}

	@Override
	public String getLastSql()
	{
		return lastSql.get();
	}
}
