package com.thejavalistener.jpqlconsole.sql;

import jakarta.persistence.EntityManagerFactory;

/** Captura la última sentencia SQL que un proveedor ORM envió a JDBC. */
public interface SqlCapture
{
	void register(EntityManagerFactory emf);

	String getLastSql();
}
