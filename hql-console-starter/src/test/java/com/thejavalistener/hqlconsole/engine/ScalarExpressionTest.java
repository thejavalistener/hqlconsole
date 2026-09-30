package com.thejavalistener.hqlconsole.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ScalarExpressionTest
{
	private final ScriptContext context=new ScriptContext(Clock.fixed(
			Instant.parse("2026-09-27T12:30:00Z"),ZoneOffset.UTC));

	@Test
	void evaluatesTodayAndNowWithWholeDayOffsets()
	{
		assertEquals(LocalDate.of(2026,9,17),ScalarExpression.resolve("NOW - 10",context,LocalDate.class));
		assertEquals(LocalDate.of(2026,10,2),ScalarExpression.resolve("TODAY+5",context,LocalDate.class));
		assertEquals(LocalDate.of(2026,9,27),ScalarExpression.resolve("TODAY - 0",context,LocalDate.class));
	}

	@Test
	void resolvesTypedVariablesAndKeepsTheirValueImmutable()
	{
		context.declare("fecha",LocalDate.of(2026,2,28));
		context.declare("importe",new BigDecimal("10.50"));
		assertEquals(LocalDate.of(2026,3,1),ScalarExpression.resolve("$fecha + 1",context,LocalDate.class));
		assertEquals(new BigDecimal("8.50"),ScalarExpression.resolve("$importe - 2",context,BigDecimal.class));
		assertEquals(LocalDate.of(2026,2,28),context.variable("fecha"));
	}

	@Test
	void infersSupportedLiteralTypesAndKeepsIsoDatesStrict()
	{
		assertEquals(LocalDate.of(2026,5,19),ScalarExpression.literalValue("'2026-05-19'"));
		assertEquals(10,ScalarExpression.literalValue("10"));
		assertEquals(new BigDecimal("10.50"),ScalarExpression.literalValue("10.50"));
		assertEquals(Boolean.TRUE,ScalarExpression.literalValue("true"));
		assertEquals("2026-05-19",ScalarExpression.literalValue("TEXT '2026-05-19'"));
		assertThrows(IllegalArgumentException.class,() -> ScalarExpression.literalValue("'2026-5-19'"));
	}

	@Test
	void rejectsExpressionsOutsideTheSmallGrammar()
	{
		assertThrows(IllegalArgumentException.class,() -> ScalarExpression.resolve("NOW + -1",context,LocalDate.class));
		assertThrows(IllegalArgumentException.class,() -> ScalarExpression.resolve("TODAY + 1.5",context,LocalDate.class));
		assertThrows(IllegalArgumentException.class,() -> ScalarExpression.resolve("NOW + 1 + 2",context,LocalDate.class));
		assertThrows(IllegalArgumentException.class,() -> ScalarExpression.resolve("$missing",context,LocalDate.class));
	}
}
