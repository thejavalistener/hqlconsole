package thejavalistener.hqlconsole.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HqlConsolePropertiesTest
{
	@Test
	void defaultHelpUrlPointsToTheConsoleManual()
	{
		assertEquals("https://raw.githubusercontent.com/thejavalistener/hqlconsole/main/hql-console-starter/src/main/resources/thejavalistener/hqlconsole/web/hql-console-help.html",
				new HqlConsoleProperties().normalizedHelpUrl());
	}

	@Test
	void blankHelpUrlForcesThePackagedFallback()
	{
		HqlConsoleProperties properties=new HqlConsoleProperties();
		properties.setHelpUrl("");

		assertEquals("",properties.normalizedHelpUrl());
	}

	@Test
	void httpsHelpUrlIsTrimmedAndAccepted()
	{
		HqlConsoleProperties properties=new HqlConsoleProperties();
		properties.setHelpUrl("  https://github.com/acme/hqlconsole/blob/main/MANUAL.md  ");

		assertEquals("https://github.com/acme/hqlconsole/blob/main/MANUAL.md",properties.normalizedHelpUrl());
	}

	@Test
	void nonHttpsHelpUrlIsRejected()
	{
		HqlConsoleProperties properties=new HqlConsoleProperties();
		properties.setHelpUrl("http://example.test/manual");

		assertThrows(IllegalArgumentException.class,properties::normalizedHelpUrl);
	}
}
