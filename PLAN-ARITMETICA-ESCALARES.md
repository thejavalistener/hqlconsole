# Plan de implementación: aritmética temporal y variables escalares

## Objetivo

Extender el lenguaje de scripts de la consola HQL con dos capacidades, sin
convertirlo en un lenguaje de programación:

1. Fechas relativas en asignaciones propias de la consola: `NOW`, `TODAY`,
   `NOW +/- N` y `TODAY +/- N`, donde `N` representa días enteros.
2. Variables escalares por ejecución de script, primero las que ya representan
   IDs generados por `INSERT`, y después las obtenidas mediante un `SELECT`
   escalar de exactamente una fila y una columna.

El alcance es deliberadamente acotado: **no** habrá `if`, bucles, listas,
colecciones, interpolación de texto, aritmética de meses/años ni sustitución
textual de HQL. Ante el primer error de sintaxis, tipo, cardinalidad o
ejecución, el script se detiene y su transacción se revierte.

## Estado actual del código

La base ya existe, por lo que no hace falta diseñar un intérprete desde cero:

- `AttributeBinder` reconoce `NOW`, `NOW()`, `CURRENT_DATE`,
  `CURRENT_TIMESTAMP` y `CURRENT_TIME`, y los convierte al tipo del atributo
  destino.
- `StatementParser` convierte los `INSERT ... VALUES` y `UPDATE ... SET` de la
  consola a `Statement.Assignment(path, literal)`.
- `BatchPlan` reconoce `$nombre = INSERT ... VALUES (...)`, valida que la
  variable no se redefina y que se use después de ser declarada.
- `HqlQueryRunner.executeBatch(...)` abre un único `EntityManager` y una única
  transacción; hoy conserva los IDs generados en un `Map<String,Object>` local
  al lote.
- `HqlConsoleController` divide el texto por `;`, controla `allow-writes` y
  envía varias sentencias a `executeBatch`.

El diseño nuevo debe conservar estas propiedades: `NOW` sin aritmética sigue
funcionando, los lotes actuales con IDs generados siguen siendo compatibles y
no quedan variables en el singleton `HqlQueryRunner`, en HTTP session ni en la
base.

## Contrato del lenguaje

### Fechas relativas: primera entrega funcional

En valores de un `INSERT ... VALUES` o `UPDATE ... SET` propios de la consola:

```hql
INSERT INTO PromocionVigencia (fechaInicio, fechaFin)
VALUES (NOW - 10, TODAY + 5);
```

Formas aceptadas:

```text
NOW
NOW()
TODAY
NOW + 5
NOW - 10
TODAY+5
TODAY - 0
```

Reglas:

- El desplazamiento es siempre una cantidad entera de **días**, sin signo en
  el número. El signo lo aporta `+` o `-`.
- `NOW` y `TODAY` son equivalentes para la aritmética de días. Se recomienda
  `TODAY` en documentación de campos de fecha y se mantiene `NOW` por
  compatibilidad.
- Dentro de una misma ejecución se captura una única fecha/hora de referencia;
  un script iniciado justo antes de medianoche no puede obtener dos “hoy”
  distintos.
- `NOW` sin `+/-` conserva el comportamiento vigente según el tipo destino
  (`LocalDate`, `LocalDateTime`, `Instant`, etc.). `TODAY` sin desplazamiento
  se acepta sólo en tipos fecha/hora y representa la fecha actual; en un tipo
  con hora se documentará que usa el inicio del día local.
- `NOW/TODAY +/- N` sólo se admite inicialmente para destinos de fecha/hora.
  En `LocalDate` se suma/resta días; en los tipos con hora se suma/resta días
  conservando la hora de referencia. No se admiten meses ni años.
- Una fecha que no existe por el desplazamiento no es un problema:
  `LocalDate.plusDays` resuelve correctamente cambios de mes, año y bisiestos.
- Expresiones como `NOW + -1`, `NOW + 1.5`, `NOW + $n`, `NOW + 1 + 2`,
  `fecha + 10` y texto entre comillas se rechazan con un error de gramática
  claro. La extensión de expresiones compuestas queda fuera de alcance.

### Variables escalares: segunda entrega funcional

Se mantienen los IDs generados y se generaliza el concepto de variable:

```hql
$autor = INSERT INTO Autor (nombre) VALUES ('Ada');

$fec = SELECT o.fechaEntregada
       FROM Orden o
       WHERE o.idOrden = 4;

INSERT INTO PromocionVigencia (fechaInicio, fechaFin)
VALUES ($fec + 10, $fec + 20);
```

Reglas:

- La variable es válida sólo dentro de la ejecución actual del script/lote.
  Una nueva ejecución empieza con un contexto vacío.
- Un nombre tiene la forma `$[A-Za-z_][A-Za-z0-9_]*`, no puede redefinirse y
  debe declararse antes de utilizarse.
- `$nombre = INSERT ... VALUES (...)` conserva su significado: guarda el ID
  generado por la entidad persistida.
- `$nombre = SELECT ...` debe ser HQL de lectura y devolver exactamente **una
  fila y una columna**. El valor debe ser escalar y no nulo.
- Se admiten inicialmente: números (`Byte`, `Short`, `Integer`, `Long`,
  `BigInteger`, `BigDecimal`, `Float`, `Double`), `Boolean`, `String`, `UUID`,
  enums y temporales Java (`LocalDate`, `LocalDateTime`, etc.). No se admiten
  entidades, proxies, arrays, mapas ni colecciones.
- Si el `SELECT` devuelve 0 filas, más de 1 fila, más de 1 columna, `null` o
  una entidad/colección, falla con un mensaje que mencione la variable y la
  condición incumplida.
- Una referencia sola (`$fec`, `$autor`) puede asignarse a un atributo sólo si
  su tipo es compatible con el del atributo. Para relaciones se conserva el
  uso actual: el escalar debe ser el ID compatible de la entidad relacionada.
- En esta entrega se permite `$fecha +/- N` cuando la variable contiene una
  fecha/hora y `$numero +/- N` cuando contiene un número. No se permite
  variable con variable, multiplicación, división, concatenación ni funciones.
- Las variables se usan como valores tipados, nunca como fragmentos de HQL:
  no sirven para sustituir una entidad, atributo, cláusula `WHERE` o texto de
  consulta.

### Semántica de script y transacciones

Un script que contiene declaraciones escalares y escrituras se ejecuta en una
única transacción resource-local, con un `EntityManager` y un contexto escalar
por ejecución. Los `SELECT` escalares ven los `INSERT`/`UPDATE` anteriores del
mismo script tras el `flush` necesario.

Si cualquier sentencia falla, se hace rollback de todas las escrituras de ese
script, se descarta el contexto de variables y no se ejecutan sentencias
posteriores. `SET AUTOCOMMIT ON` seguirá significando sólo “no pedir
confirmación en la interfaz”; **no** rompe la atomicidad del script.

El resultado HTTP puede conservar el resumen actual de filas afectadas, pero
no debe devolver el contenido de variables salvo que se diseñe una vista
explícita en otra entrega: algunos valores podrían ser sensibles.

## Diseño técnico propuesto

### Contexto tipado de ejecución

Crear una clase de motor, por ejemplo `ScriptContext`, con:

```java
final class ScriptContext {
    private final Clock clock;
    private final Instant startedAt;
    private final Map<String, Object> scalars;
}
```

Responsabilidades:

- Capturar el reloj al inicio de `execute(...)` o `executeBatch(...)`.
- Resolver `TODAY` y la hora de referencia de una única ejecución.
- Declarar una variable una sola vez y obtenerla con error claro si falta.
- Mantener tipos Java reales, no serializaciones ni `String.valueOf(...)`.
- No escapar al método de ejecución.

El `Map<String,Object> generatedIds` actual de `HqlQueryRunner` se reemplaza
por este contexto; de ese modo un ID generado y un resultado de `SELECT`
comparten el mismo mecanismo sin perder tipo.

### Evaluador mínimo de expresiones escalares

Crear una clase pura y testeable, por ejemplo `ScalarExpression`, que reciba
el literal completo de una asignación, el `ScriptContext` y el tipo de destino.
Debe reconocer sólo expresiones completas, nunca fragmentos mediante `replace`.

Orden de resolución recomendado:

1. `null` y los literales existentes.
2. Referencia exacta `$nombre`.
3. Temporal especial `NOW`/`NOW()`/`TODAY`, con o sin `+/- días`.
4. Temporal variable `$nombre +/- días`.
5. Número variable `$nombre +/- entero`.
6. Si nada coincide, delegar a la conversión existente de `AttributeBinder`.

`AttributeBinder.value(...)` debe pedir primero el valor a este evaluador y
después convertir/adaptar al tipo de `Target`. No se debe usar
`String.valueOf(variable)` porque degrada fechas, UUID, enums y precisión
decimal. La compatibilidad se valida por tipo y la conversión numérica debe ser
explícita, sin pérdida silenciosa (`BigDecimal` a `Integer` con fracción, por
ejemplo, falla).

Para evitar errores de fecha no deterministas, inyectar un `Clock` en la capa
que crea `ScriptContext`. Producción usa `Clock.systemDefaultZone()`; tests
usan `Clock.fixed(...)`. Para preservar la API pública, `AttributeBinder` puede
mantener el constructor actual y delegar en un reloj por defecto, mientras el
runner usa el constructor nuevo/contexto en los scripts.

### Plan de script

Extender `BatchPlan` sin romper las sentencias actuales:

- Reemplazar `Entry(statement, generatedIdVariable)` por una entrada que
  distinga `INSERT_GENERA_ID`, `SELECT_ESCALAR` y `ESCRITURA`.
- Conservar `statement()` y `generatedIdVariable()` mientras haya consumidores,
  o ajustar todos los consumidores en el mismo cambio con pruebas de
  compatibilidad.
- La declaración se reconoce una sola vez con la gramática actual
  `$nombre = ...`.
- Después de `=`, aceptar sólo `INSERT ... VALUES (...)` o `SELECT ...`.
  Rechazar `UPDATE`, `DELETE`, `DESC`, SQL nativo y `INSERT ... SELECT` como
  declaración de variable.
- En el análisis estático, validar nombre no repetido y referencias anteriores
  en asignaciones de consola. Para `$var + N`, el analizador de expresiones es
  el que informa la dependencia; no reutilizar el regex actual que sólo acepta
  `$var` como literal entero.
- El `SELECT` se valida estructuralmente durante la ejecución: Hibernate es la
  autoridad para su sintaxis HQL y el resultado real para su cardinalidad.

No se habilita un `SELECT` normal mezclado con escrituras: dentro de un script
multi-sentencia un `SELECT` sólo es legal si declara una variable. La consulta
para mostrar filas sigue siendo una ejecución individual, como hoy. Esto evita
tener que decidir cómo combinar grillas de resultados y DML en una respuesta.

### Ejecución

En `HqlQueryRunner.executeBatch(...)`:

1. Construir y validar completamente `BatchPlan` antes de abrir la
   transacción. Un error de orden, nombre o referencia no toca la base.
2. Crear `ScriptContext` y abrir `EntityManager`/transacción una sola vez.
3. Recorrer las entradas en orden.
4. Para `INSERT_GENERA_ID`, reutilizar `_insert(...)`, hacer `flush`, obtener
   el ID y declararlo en el contexto.
5. Para `SELECT_ESCALAR`, ejecutar `em.createQuery(hql)`, pedir como máximo dos
   filas sólo para diagnosticar “más de una”, verificar una columna y el tipo
   escalar, y declarar el valor tipado.
6. Para escrituras, reutilizar `_runBatchStatement(...)` pasando el contexto al
   binder/evaluador.
7. Ante cualquier `RuntimeException`, agregar al error el número de sentencia
   y, si corresponde, el nombre de variable; hacer rollback y propagarlo.
8. Sólo si todo termina correctamente, hacer commit. Cerrar siempre el
   `EntityManager` y descartar el contexto.

El controller debe adaptar `_hasWrite(...)`: una declaración `$x = SELECT ...`
no es escritura por sí misma, pero un script que además contiene DML requiere
`hql-console.allow-writes=true`. Conviene delegar esta clasificación al plan
para no duplicar la gramática en controller y motor.

## Fases de implementación y puertas de avance

La regla de trabajo es estricta: **no empieza una fase si la anterior no
compila, todas sus pruebas pasan y su comportamiento previo sigue cubierto**.
Cada fase termina con `gradlew.bat test` y, cuando cambia la integración web,
con `verify-demo.ps1`. Este último requiere el entorno con permisos completos,
tal como ya documenta `PENDIENTES.md`.

### Fase 0 — Línea base y pruebas de caracterización

**Objetivo:** conocer el punto de partida y congelar compatibilidad.

1. Ejecutar `gradlew.bat test` y `verify-demo.ps1`; registrar el conteo de
   PASS/FAIL actual en el commit o PR.
2. Agregar pruebas de caracterización de `AttributeBinder` para `NOW` existente
   en `LocalDate`, `LocalDateTime` y un tipo no temporal que debe fallar.
3. Agregar o aislar pruebas unitarias de `BatchPlan` para el lote actual
   `$id = INSERT ... VALUES` seguido de una referencia.
4. Verificar que los datos de demo contengan al menos un temporal que permita
   probar inserciones y consultas escalares sin depender de IDs externos.

**Puerta:** build verde, demo verde y ningún cambio funcional todavía.

### Fase 1 — `NOW` / `TODAY +/- días`

**Objetivo:** habilitar fechas relativas sólo en asignaciones de la gramática
propia de consola.

1. Introducir `ScriptContext` con `Clock` fijo por ejecución, sin variables
   nuevas todavía.
2. Implementar el parser/evaluador puro de las seis formas temporales del
   contrato. Debe exigir que toda la expresión sea válida.
3. Integrarlo con `AttributeBinder` antes de la conversión normal, preservando
   `NOW`, `NOW()`, `CURRENT_DATE`, `CURRENT_TIMESTAMP` y `CURRENT_TIME` ya
   soportados.
4. Pasar el contexto tanto al INSERT como al UPDATE de la consola, individual y
   en lote. Una ejecución individual también recibe contexto fresco.
5. No tocar HQL bulk: `update Producto p set ...` sigue siendo HQL del motor y
   no recibe esta gramática. Es importante no confundir la ayuda de consola con
   expresiones HQL nativas.

**Pruebas unitarias obligatorias:**

- Con `Clock.fixed(2026-09-27T12:00:00Z, ...)`, `NOW - 10`, `TODAY + 5` y
  `TODAY - 0` producen las fechas esperadas.
- Cruces de mes, año y año bisiesto.
- `NOW + 1` funciona en `LocalDate`; para `LocalDateTime` conserva la hora de
  referencia y cambia sólo el día.
- `NOW`, `NOW()`, `CURRENT_DATE` y el resto de compatibilidad anterior siguen
  pasando.
- Rechazo de cada forma fuera de alcance (`NOW + -1`, decimal, doble operador,
  más de una operación, texto citado, atributo libre).
- Rechazo de aritmética temporal asignada a `String`, `Integer` o relación.

**Pruebas de integración obligatorias:**

- INSERT con `TODAY - 1` y UPDATE con `NOW + 1` persisten el valor esperado.
- Un lote captura el mismo “hoy” en dos sentencias.
- Una sentencia inválida no inserta ni actualiza ninguna fila.

**Puerta:** build y demo verdes. No avanzar a variables de SELECT si un caso de
compatibilidad de `NOW` cambia o si se acepta una expresión fuera del contrato.

### Fase 2 — Contexto escalar compatible con IDs generados

**Objetivo:** sustituir el mapa de IDs por el contexto tipado sin agregar aún
`SELECT` escalar.

1. Hacer que `$id = INSERT ... VALUES` declare el ID en `ScriptContext`.
2. Resolver `$id` exacto usando el valor tipado, incluso para relaciones.
3. Ajustar `BatchPlan` para que la validación de referencias use el mismo
   analizador de expresiones que se usará después.
4. Asegurar que las variables no aparecen en `execute(...)` de una sola
   sentencia ni sobreviven entre dos llamadas a `executeBatch(...)`.

**Pruebas obligatorias:**

- Todas las pruebas existentes de `GeneratedIdBatchTest` y `BatchPlanTest`.
- ID generado utilizado como FK, en dos ejecuciones distintas, sin contaminación.
- Variable indefinida, redefinida y usada antes de declararse: error antes de
  abrir transacción o de afectar filas.
- Un rollback posterior a un INSERT no deja ni entidad ni variable reutilizable.

**Puerta:** comportamiento de `$id = INSERT` idéntico al actual y cero
regresiones. No se agrega SELECT hasta que esta migración esté cerrada.

### Fase 3 — Declaración `$var = SELECT escalar`

**Objetivo:** permitir obtener valores tipados de HQL con cardinalidad estricta.

1. Extender `BatchPlan.Entry` y la gramática de declaraciones para distinguir
   el SELECT escalar de una escritura.
2. Aceptar sólo la palabra inicial `select` o `from` (si la política admite la
   forma abreviada de HQL) después de `=`; documentar una única decisión y
   probarla. Recomendación: exigir `SELECT` explícito en esta primera versión,
   porque hace inequívoca la “única columna”.
3. Implementar `_selectScalar(...)` en `HqlQueryRunner`, dentro de la misma
   transacción de lote, con `setMaxResults(2)` y validación exacta de filas,
   columnas, null y tipo escalar.
4. Declarar el valor en `ScriptContext` sólo después de que todas esas
   validaciones pasen.
5. Mantener el SELECT en HQL y nunca en SQL nativo: el modo SQL es de sólo
   lectura y no comparte el motor de scripts con escritura.
6. Adaptar el controller para que un lote con SELECT escalar y DML respete
   `allow-writes`, mientras una declaración de SELECT sin DML no eluda reglas
   de lote ni se convierta en una grilla normal.

**Pruebas unitarias obligatorias:**

- Parser acepta `$fec = SELECT o.fechaEntregada FROM Orden o WHERE o.idOrden = 4`.
- Parser rechaza `$x = UPDATE ...`, `$x = DELETE ...`, `$x = DESC ...`,
  `$x = INSERT ... SELECT ...` y nombre mal formado.
- Nombre repetido y referencia anticipada fallan de modo determinista.

**Pruebas de integración obligatorias:**

- SELECT de `LocalDate` con una fila seguido de INSERT usando `$fec`.
- SELECT de ID o número seguido de asignación compatible.
- 0 filas, 2 filas, 2 columnas, `null`, entidad y colección: cada caso falla
  con mensaje específico y rollback completo de una escritura anterior.
- Un SELECT ve el INSERT precedente del mismo script cuando la secuencia lo
  requiere.

**Puerta:** todas las cardinalidades fallidas hacen rollback; ninguna ejecución
posterior empieza si el SELECT no dejó una variable válida.

### Fase 4 — Aritmética sobre variables escalares

**Objetivo:** aplicar la aritmética mínima, segura y tipada del contrato.

1. Añadir `$fecha +/- días` usando las mismas funciones temporales de Fase 1.
2. Añadir `$numero +/- entero`, con promoción/control explícito de tipos.
3. Definir conversiones permitidas hacia el atributo destino. Recomendación:
   conservar el tipo del operando cuando sea seguro; para enteros, detectar
   overflow; para `BigDecimal`, usar suma/resta exacta con un entero convertido
   a `BigDecimal`.
4. No aceptar operaciones para `String`, boolean, UUID, enum, relación ni
   variables null (que en Fase 3 ya se rechazan al declarar).

**Pruebas obligatorias:**

- `$fec + 10` y `$fec - 1` en una inserción/actualización de fecha.
- `$cantidad + 1` y `$importe - 2` con tipos numéricos soportados.
- Límite de `Integer`/`Long`, fracción no representable, atributo incompatible
  y todos los operadores no soportados fallan sin efectos.
- `$var + 1` no modifica el valor almacenado de `$var`; las variables son
  inmutables una vez declaradas.

**Puerta:** sólo se habilita la sintaxis documentada; cualquier expresión más
rica sigue fallando de manera explícita, sin ser enviada parcialmente a
Hibernate.

### Fase 5 — Integración HTTP/UI y documentación breve

**Objetivo:** que la consola explique el feature sin volver la interfaz un IDE.

1. Mantener el editor y el endpoint actuales; no se agregan controles de flujo
   ni panel de variables.
2. Agregar ayuda breve visible o enlazable desde la consola, con cuatro ejemplos
   seguros: `NOW - 1`, `TODAY + 7`, `$id = INSERT`, `$fec = SELECT ...`.
3. Actualizar `README.md` y `PENDIENTES.md` con el alcance, restricciones,
   semántica de rollback y ejemplos.
4. Crear el manual breve pendiente indicado abajo.
5. Extender `verify-demo.ps1` con ejecuciones HTTP de las rutas felices y de
   errores/rollback. No incorporar tildes en patrones/literales de PowerShell
   5.1, conforme a las convenciones existentes.

**Puerta:** `verify-demo.ps1` verde en ejecución normal y con `-ContextPath`;
la página sigue pasando `node --check` y las funciones puras nuevas, si las hay,
quedan dentro de sus marcadores para que el script las pruebe.

## Matriz de errores esperados

| Situación | Comportamiento exigido |
|---|---|
| `NOW + -1` | Error de sintaxis: usar `NOW - 1`. No se ejecuta el script. |
| `TODAY + 1.5` | Error: los días deben ser enteros. |
| `$x` no declarada | Error que nombra `$x` y la sentencia. |
| `$x` redefinida | Error antes de la ejecución. |
| `$x = SELECT ...` con 0/2+ filas | Error de cardinalidad y rollback. |
| SELECT con 2 columnas | Error: se espera una sola columna escalar. |
| SELECT devuelve `null` o entidad | Error de valor/tipo no admitido y rollback. |
| `$fecha + 1` hacia entero | Error de incompatibilidad de tipos. |
| `$texto + 1` | Error: aritmética no definida para texto. |
| Fallo en sentencia posterior | Rollback total y contexto descartado. |
| Nueva ejecución | No conoce variables de una ejecución anterior. |

## Manual de uso breve: pendiente explícito

Queda pendiente crear `MANUAL-RAPIDO.md` (o una sección equivalente y breve en
`README.md`). No debe ser un manual kilométrico. Su propósito es que alguien
pueda abrir la consola y usarla en pocos minutos.

Contenido propuesto, en una o dos pantallas:

1. Dónde abrir la consola y cómo ejecutar una sentencia o el párrafo actual.
2. `DESC`, `SELECT`, `SELECT *`, `LIMIT` y comentarios.
3. `INSERT ... VALUES` y `UPDATE ... SET`; recordar que los nombres son de
   entidades/atributos Java, no tablas/columnas SQL.
4. Lotes separados por `;`, atomicidad y la advertencia de escritura.
5. IDs generados:

   ```hql
   $autor = INSERT INTO Autor (nombre) VALUES ('Ada');
   INSERT INTO Libro (titulo, autor) VALUES ('Notas', $autor);
   ```

6. Fechas relativas:

   ```hql
   INSERT INTO Libro (fechaAlta) VALUES (TODAY - 1);
   ```

7. SELECT escalar y aritmética de variable:

   ```hql
   $fec = SELECT o.fechaEntregada FROM Orden o WHERE o.idOrden = 4;
   UPDATE Recordatorio SET fecha = $fec + 10 WHERE id = 1;
   ```

8. Límites visibles: una fila/columna escalar, no listas, no bucles, no `if` y
   rollback ante error.

El manual se escribe cuando Fase 5 esté aprobada: así no documenta sintaxis que
todavía no exista.

## Criterio de terminado

La funcionalidad estará terminada sólo si:

- se implementaron las fases 1 a 5 en orden, sin saltar sus puertas;
- todos los tests unitarios, de integración y `verify-demo.ps1` están verdes;
- `NOW` existente, IDs generados y el resto de la consola no regresaron;
- los fallos no dejan escrituras parciales ni variables reutilizables;
- `README.md`, `PENDIENTES.md` y el manual breve describen el contrato final;
- el demo muestra, como mínimo, una fecha relativa vigente/vencida y un SELECT
  escalar que alimente una operación posterior de manera atómica.
