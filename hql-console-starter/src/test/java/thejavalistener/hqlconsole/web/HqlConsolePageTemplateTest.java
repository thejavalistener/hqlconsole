package thejavalistener.hqlconsole.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HqlConsolePageTemplateTest
{
	private static final String RESOURCE="/thejavalistener/hqlconsole/web/hql-console.html";
	private static final String CONFIG_MARKER="__HQL_CONSOLE_CONFIG__";

	@Test
	void templateIsPackagedAsUtf8AndHasOneConfigurationMarker() throws IOException
	{
		try(InputStream input=HqlConsolePageTemplateTest.class.getResourceAsStream(RESOURCE))
		{
			assertNotNull(input,"Falta la plantilla HTML en el classpath: "+RESOURCE);
			String template=new String(input.readAllBytes(),StandardCharsets.UTF_8);
			assertTrue(template.contains("<meta charset=\"utf-8\">"),"La plantilla no se leyó como UTF-8.");
			assertEquals(1,count(template,CONFIG_MARKER),"El marcador de configuración debe aparecer exactamente una vez.");
			assertTrue(template.contains("id=\"hql-console-config\""),"Falta el bloque JSON de configuración.");
			assertTrue(template.contains("id=\"panel-editor\"")&&template.contains("id=\"panel-resultado\""),
					"La plantilla perdió paneles esenciales.");
			assertEquals(0,count(template,"__BASE__")+count(template,"__MAX_ROWS__")+count(template,"__ALLOW_WRITES__"),
					"La plantilla conserva marcadores de la implementación anterior.");
		}
	}

	@Test
	void rendererInjectsSafeJsonAndRemovesTheMarker()
	{
		String page=HqlConsolePage.render("<script>__HQL_CONSOLE_CONFIG__</script>","/demo/hqlconsole",3);

		assertEquals("<script>{\"base\":\"/demo/hqlconsole\",\"maxRows\":3}</script>",page);
		assertFalse(page.contains(CONFIG_MARKER));
	}

	@Test
	void rendererRejectsMissingOrDuplicatedConfigurationMarkers()
	{
		assertThrows(IllegalArgumentException.class,()->HqlConsolePage.render("<html></html>","/hqlconsole",500));
		assertThrows(IllegalArgumentException.class,
				()->HqlConsolePage.render(CONFIG_MARKER+CONFIG_MARKER,"/hqlconsole",500));
	}

	private int count(String value,String fragment)
	{
		int count=0;
		int offset=0;
		while( (offset=value.indexOf(fragment,offset))>=0 )
		{
			count++;
			offset+=fragment.length();
		}
		return count;
	}
}
