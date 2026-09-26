package thejavalistener.hqlconsole.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HqlConsoleLabelTest
{
	@Test
	void recognizesOnlyTheExactPublicInstanceStringMethod()
	{
		assertTrue(HqlConsoleLabel.supports(Valid.class));
		assertFalse(HqlConsoleLabel.supports(Missing.class));
		assertFalse(HqlConsoleLabel.supports(WrongReturnType.class));
		assertFalse(HqlConsoleLabel.supports(StaticMethod.class));
	}

	@Test
	void normalizesWhitespaceAndLimitsTheTextToSixtyCharacters()
	{
		assertEquals("Ana Gomez",HqlConsoleLabel.read(new Valid("  Ana\n\t Gomez  ")));

		String longText="x".repeat(HqlConsoleLabel.MAX_LENGTH+1);
		String label=HqlConsoleLabel.read(new Valid(longText));
		assertEquals(HqlConsoleLabel.MAX_LENGTH,label.codePointCount(0,label.length()));
		assertTrue(label.endsWith("…"));
	}

	@Test
	void fallsBackWhenTheMethodCannotProvideUsefulText()
	{
		assertNull(HqlConsoleLabel.read(new Missing()));
		assertNull(HqlConsoleLabel.read(new Valid(" \n\t ")));
		assertNull(HqlConsoleLabel.read(new Failing()));
	}

	public static final class Valid
	{
		private final String text;

		Valid(String text)
		{
			this.text=text;
		}

		public String toHqlConsoleString()
		{
			return text;
		}
	}

	public static final class Missing {}

	public static final class WrongReturnType
	{
		public CharSequence toHqlConsoleString()
		{
			return "no";
		}
	}

	public static final class StaticMethod
	{
		public static String toHqlConsoleString()
		{
			return "no";
		}
	}

	public static final class Failing
	{
		public String toHqlConsoleString()
		{
			throw new IllegalStateException("no disponible");
		}
	}
}
