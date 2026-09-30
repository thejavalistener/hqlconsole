package com.thejavalistener.hqlconsole.sql;

import jakarta.persistence.EntityManagerFactory;

/** Implementación segura para proveedores sin un hook compatible. */
public final class NoOpSqlCapture implements SqlCapture
{
	@Override
	public void register(EntityManagerFactory emf) { }

	@Override
	public String getLastSql() { return null; }
}
