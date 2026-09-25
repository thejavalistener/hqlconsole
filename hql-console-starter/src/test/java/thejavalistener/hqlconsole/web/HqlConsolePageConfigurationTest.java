package thejavalistener.hqlconsole.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class HqlConsolePageConfigurationTest
{
	@Test
	void writesOrdinaryBaseAndNumberAsJson()
	{
		assertEquals("{\"base\":\"/demo/hqlconsole\",\"maxRows\":3}",
				HqlConsolePageConfiguration.json("/demo/hqlconsole",3));
	}

	@Test
	void escapesCharactersThatCouldBreakJsonOrTheScriptElement()
	{
		String json=HqlConsolePageConfiguration.json("\"\\\n</script>&\u2028",0);

		assertEquals("{\"base\":\"\\\"\\\\\\n\\u003c/script\\u003e\\u0026\\u2028\",\"maxRows\":0}",json);
		assertFalse(json.contains("</script>"));
	}
}
