# hql-console — consola HQL para Spring Boot

Un jar. Lo ponés en el classpath de tu aplicación Spring Boot, levantás el server, y tenés una
consola HQL en `http://localhost:8080/hqlconsole` ejecutando consultas contra el `EntityManager`
**vivo** de tu aplicación.

```
┌─ HQL Console ────────────────────────────┬───────────────────────────┐
│ SELECT e.id, e.nombre, e.salario         │  6 filas en 7 ms          │
│ FROM Empleado e                          │  id | nombre    | salario │
│                                          │  1  | Ana Gomez | 1500000 │
│                                          │  2  | Bruno Diaz| 1200000 │
│ Ctrl+Enter: el párrafo del cursor (38)   │                           │
│                          [ Ejecutar ]    │                           │
└──────────────────────────────────────────┴───────────────────────────┘
        ↑ el divisor del medio se arrastra
```

## Por qué no alcanza la consola HQL del IDE

IntelliJ o Hibernate Tools levantan **su propia** `SessionFactory` contra la base: no ven los
`@Filter`, ni el multi-tenant, ni la caché de segundo nivel, ni el estado transaccional de tu app,
y no llegan a una base embebida o a un Testcontainers. Esta consola se cuelga del contexto que ya
está corriendo, así que consulta exactamente lo mismo que consulta tu código.

## Uso

```gradle
dependencies {
    implementation 'com.github.thejavalistener:hql-console-starter:0.1.0'
}
```

Eso es todo. No hay `@Import`, ni `@ComponentScan`, ni `@EnableHqlConsole`, ni una línea de
configuración: el jar trae su auto-configuración declarada en
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` y Spring Boot la
descubre sola.

Al arrancar, el log avisa dónde quedó:

```
WARN  Consola HQL en http://localhost:8080/hqlconsole  [escrituras HABILITADAS]
      — herramienta de desarrollo, no la dejes habilitada en producción
```

### "Tirar el jar en el classpath"

Las dos formas que funcionan:

1. **Como dependencia** (lo normal): el `implementation` de arriba, o el `<dependency>` de Maven.
2. **Copiando el jar a mano** dentro de `BOOT-INF/lib/` de un fat jar ya construido.

Lo que **no** funciona es `java -cp app.jar;hql-console-starter.jar`: un fat jar de Spring Boot usa
su propio classloader (`JarLauncher`), así que el `-cp` de la línea de comandos no llega.

> **Importante al publicar:** el jar de la consola **no debe repackagarse** con
> `spring-boot-maven-plugin`. Si se repackagea, `META-INF/spring/...imports` termina dentro de
> `BOOT-INF/classes/`, donde el classloader de la aplicación que lo consume no lo busca: la
> auto-configuración no se descubre y las clases ni siquiera cargan. Por eso este módulo es un
> `java-library` puro, sin plugin de Boot.

### Puerto y URL

El puerto es el de **tu aplicación**, no el de la consola: la consola se cuelga del servidor que ya
tenés, así que se configura con la propiedad `server.port` de Spring Boot y no hay nada que tocar
del lado de la consola.

```properties
# src/main/resources/application.properties
server.port=8081
```

```yaml
# o application.yml
server:
  port: 8081
```

Y también sin tocar archivos:

```bash
java -jar miapp.jar --server.port=8081          # argumento de la aplicación (tiene prioridad)
java -Dserver.port=8081 -jar miapp.jar          # propiedad del sistema (va antes del -jar)
SERVER_PORT=8081 java -jar miapp.jar            # variable de entorno
                                                # en PowerShell: $env:SERVER_PORT=8081; java -jar miapp.jar
```

Con `server.port=0` Tomcat elige un puerto libre, y el log de la consola te dice cuál: lee
`local.server.port`, la misma propiedad que resuelve `@LocalServerPort`.

Si cambiás el `server.servlet.context-path`, la consola se mueve con él sin que hagas nada
(`http://localhost:8081/miapp/hqlconsole`). Y si querés cambiar sólo la ruta de la consola:
`hql-console.path=/consola`.

## Propiedades (`hql-console.*`)

| Propiedad | Default | Descripción |
|---|---|---|
| `enabled` | `true` | Kill switch. `false` deja el jar completamente inerte. |
| `path` | `/hqlconsole` | Ruta base. Tiene que empezar con `/`. |
| `max-rows` | `500` | Tope de filas por consulta (0 = sin tope). Avisa cuando trunca. |
| `allow-writes` | `true` | Habilita `update` / `delete`. |
| `show-stacktrace` | `false` | Incluye el stacktrace completo en la respuesta de error. |

Para dejarla apagada en producción alcanza con `hql-console.enabled=false`, o directamente no
poner el jar en ese entorno.

## Sentencias

### Qué se ejecuta

- **Con texto pintado**, `Ctrl+Enter` (o el botón) ejecuta **sólo la selección** y el resto se
  ignora. La página lo avisa al pie del editor: *"se ejecutará sólo la selección (N caracteres)"*.
- **Sin nada seleccionado**, ejecuta el **párrafo donde está el cursor**: desde la línea en blanco
  de arriba hasta la de abajo. Un párrafo es un bloque de líneas no vacías; lo que separa párrafos
  es una o más líneas en blanco (o con sólo espacios).
- **Ya no hay "ejecutar todo"**: sólo se ejecuta el textarea completo cuando todo el textarea es un
  único párrafo (o sea, cuando no hay ninguna línea en blanco en el medio).

Así podés dejar varias consultas separadas por líneas en blanco y correr la que quieras con sólo
poner el cursor adentro:

```sql
-- los que más ganan
SELECT e.nombre, e.salario FROM Empleado e ORDER BY e.salario DESC

-- cuántos hay por departamento
SELECT d.nombre, count(e) FROM Empleado e JOIN e.departamento d GROUP BY d.nombre
```

Detalles del párrafo, que están cubiertos por los tests:

- El límite son las líneas en blanco: el párrafo se lleva también sus líneas de comentario.
- El cursor al final de una línea pertenece a **esa** línea, no a la de abajo.
- Si el cursor cae en una línea en blanco (no hay párrafo propio), se usa el de **arriba** y, si no
  hay, el de **abajo**. Si el textarea está vacío, avisa que no hay nada que ejecutar.
- Una selección de sólo espacios en blanco cuenta como "sin selección" y cae en el párrafo.
- Si pegás texto con CRLF de Windows, el retorno de carro se descarta antes de mandarlo.

### La página

- **Dos paneles con divisor movible.** El editor queda a la izquierda y los resultados (la grilla,
  el resumen y el JSON crudo) a la derecha. El botón **Ejecutar** vive dentro del panel izquierdo,
  debajo del textarea y alineado a la derecha; al lado está el aviso de qué se va a ejecutar. El
  divisor del medio se arrastra con el mouse; con el foco puesto en él, las flechas lo mueven de a
  2% (con `Shift`, de a 10%), `Inicio`/`Fin` van a los extremos y el doble clic vuelve a 50/50. El
  ancho elegido se recuerda. Por debajo de 720 px de ancho los paneles se apilan y el divisor
  desaparece.
- **El texto del editor es persistente.** Lo que escribís queda en el `localStorage` del navegador y
  reaparece la próxima vez que abrís la página: sobrevive a recargar, a cerrar el navegador y a
  bajar y volver a levantar la aplicación. Se guarda mientras tipeás (con un retardo de 400 ms) y
  también al ejecutar y al cerrar la pestaña, así que no hace falta ejecutar para no perderlo. El
  almacén es lo único que se comparte entre aplicaciones distintas servidas desde el mismo
  `host:puerto`; si querés borrarlo, limpiá los datos del sitio.
- **La página se sirve con `Cache-Control: no-store`.** Si el navegador cacheara el HTML, un cambio
  en la consola seguiría invisible hasta un `Ctrl+F5`, con la sensación de que el jar no se actualizó.

Las **entidades y los atributos son case sensitive**, igual que en HQL: `SELECT l.titulo FROM Libro l`
funciona, `SELECT l.tiTulo FROM libro l` no. Las palabras clave (`select`, `from`, `insert`,
`values`, `set`, `where`, `desc`, `now`) sí van en cualquier caja, como en HQL. Cuando lo único que
falla es el case, el error te lo dice: `No conozco la entidad 'libro'. ¿Quisiste decir 'Libro'?`.

### HQL

Se le pasa a Hibernate tal cual:

```sql
SELECT e.id, e.nombre FROM Empleado e
from Libro l where l.autor is not null
INSERT INTO Libro (titulo) SELECT e.nombre FROM Empleado e WHERE e.id = 1
DELETE FROM Empleado e WHERE e.nombre = 'Fabio Luna'
```

Y si omitís el `SELECT` y escribís sólo el `from`, las filas salen con **todas las columnas planas**
de la entidad, en vez de una sola columna con `Libro#1`:

```sql
from Libro                           -- id | titulo | fechaPublicacion | ... | autor | ...
from Libro l where l.precio > 10000  -- idem, filtrado
```

- Las columnas salen en el orden de declaración de la entidad y **tituladas con el nombre del
  atributo de la clase** (`fechaPublicacion`, `autor`), no con el nombre físico de la tabla. Son los
  nombres que escribiste en la entidad y los que podés volver a escribir en un HQL.
- El nombre físico sigue estando en `DESC`, en la columna `CAMPO`: `DESC Libro` es el mapa de lo que
  te devuelve `from Libro`, y sus títulos son exactamente su columna `ATRIBUTO`.
- Una relación `to-one` se muestra como **el id de la FK** (`autor` → `1`), sin inicializar el
  proxy ni traer la entidad relacionada.
- Las colecciones (`@OneToMany`) no se muestran: no son campos planos.
- Un `join fetch` también se aplana, porque la fila sigue siendo sólo la entidad. Un **join
  explícito** (`from Libro l join l.autor a`) no: la fila tiene dos raíces y sale como
  `Libro#1 | Autor#1`. Con `SELECT` explícito tampoco se aplana, porque ahí se devuelve exactamente
  lo que se pidió.

### Las tres sentencias propias de la consola

Hibernate no las conoce. Son éstas:

### `INSERT ... VALUES`

```sql
INSERT INTO Libro li VALUES li.titulo='Las mil y una noches',
                            li.fechaPublicacion='1994-11-23',
                            li.fechaAlta=NOW
```

- El **id se genera solo** si es `@GeneratedValue`. La consola te lo devuelve en el mensaje:
  `Insertado Libro#7`.
- Los **campos que no nombrás quedan en NULL**, o falla la sentencia con el error de la base si la
  columna no los acepta. No se inventan valores.
- Una **relación se asigna por el id**: `li.autor=5` y `li.autor.id=5` son equivalentes y resuelven
  la FK sin que tengas el objeto.
- El alias es opcional y la ruta puede ir con él o sin él: `li.titulo`, `titulo`, o directamente
  `INSERT INTO Libro VALUES titulo='...'`.

### `UPDATE ... SET ... WHERE ...`

```sql
UPDATE Libro li SET li.titulo='Las 1000 y dos noches',
                    li.fechaModif=NOW
              WHERE li.id=132
```

- **Sólo se modifican los campos del SET**; el resto conserva lo que tenía.
- El **`WHERE` es HQL**: se lo pasa a Hibernate dentro de un `select`, así que acepta `AND`, `OR`,
  `NOT`, `IN`, `LIKE`, `BETWEEN`, `IS NULL` y subconsultas sin que la consola sepa nada de
  expresiones.
- La ejecución **carga las entidades y las modifica**, así que disparan `@PreUpdate` y el
  `@Version` y el `UPDATE` sale en el commit. El costo es una fila por vez.
- **Sin `WHERE` modifica todas las filas**, acotado por `max-rows`, y te avisa si truncó.
- Si el `UPDATE` no entra en esta gramática se intenta como **bulk de HQL**, así que un update con
  join o con una función rara sigue funcionando.

### `DESC <Entidad>`

```sql
DESC Libro
```

| CAMPO | TIPO SQL | ATRIBUTO | TIPO JAVA |
|---|---|---|---|
| ID | BIGINT | id | Long |
| TITULO | CHARACTER VARYING | titulo | String |
| FECHA_PUBLICACION | DATE | fechaPublicacion | LocalDate |
| ID_AUTOR | BIGINT | autor | Autor |

- `CAMPO` y `TIPO SQL` son los **reales de la base**, leídos por JDBC (`DatabaseMetaData`). Sin
  `DataSource` en el contexto se derivan del mapping en vez de fallar.
- En una relación `to-one`, `CAMPO` es la columna de la FK y `ATRIBUTO`/`TIPO JAVA` son la relación y
  su entidad: `ID_AUTOR | BIGINT | autor | Autor`.
- El orden es el **de declaración de los campos en la entidad**. Una colección (`@OneToMany`) no
  tiene columna en esta tabla y sale con `-`.

### `DESC` (sin argumentos)

```sql
DESC
```

Lista todas las entidades del contexto, con su tabla y su cantidad de columnas:

| ENTIDAD | TABLA | CAMPOS |
|---|---|---|
| Autor | autores | 2 |
| Departamento | departamentos | 2 |
| Empleado | empleados | 5 |
| Libro | libros | 9 |

### Literales y `NOW`

| Literal | A qué se convierte |
|---|---|
| `'texto'` | `String`; si el campo es otra cosa, se parsea (fecha ISO, número, enum, UUID, booleano) |
| `123`, `123.45` | el numérico del campo (`Integer`, `Long`, `BigDecimal`, …) |
| `true` / `false` / `1` / `0` | `Boolean` |
| `NULL` | `null` (error claro si el campo es primitivo) |
| `'ACTIVO'` | el enum por nombre (y si no coincide, te dice cuáles hay) |
| `NOW`, `NOW()` | según el campo destino: `LocalDate`/`java.sql.Date` → fecha sin hora; `LocalDateTime`/`Timestamp`/`Instant` → fecha y hora; `LocalTime`/`java.sql.Time` → hora |
| `li.autor=5` | una referencia al `Autor` con id 5 |

`CURRENT_DATE`, `CURRENT_TIME` y `CURRENT_TIMESTAMP` se aceptan como sinónimos de `NOW`. Lo que no
se puede convertir devuelve un 400 diciendo el campo, el literal y el tipo esperado.

## Cómo se comporta

**Filas.** Cada celda se aplana a algo serializable: los escalares van tal cual, las fechas como
texto, y las entidades como `Empleado#1` usando el id del metamodelo **sin inicializar el proxy**.
Nunca se le entrega una entidad cruda a Jackson: eso daría recursión infinita, `LazyInitialization`
o un N+1 de mil filas. Las colecciones y los mapas se muestran como `«colección»` sin tocarlos.

**Nombres de columna.** Salen del alias real de la consulta cuando lo escribís con `AS`
(`getAlias()` de `Tuple`). Sin `AS`, JPQL no garantiza ningún alias, así que la consola los deduce
del propio `select`: `SELECT e.id, e.nombre` → `id`, `nombre`; `SELECT count(e)` → `count(e)`;
`from Empleado e` → `Empleado`. Si el parseo no coincide con la cantidad real de columnas, cae a
`col1..colN` en vez de mentirte.

**Errores.** Vuelven con HTTP 400 y el mensaje, la causa raíz y la sentencia que falló, para que se
vea en rojo en la página.

**Transacciones.** Un `EntityManager` nuevo por sentencia, con transacción *resource-local*: las
lecturas hacen `begin` + `rollback`, las escrituras `begin` + `commit`. No se engancha a las
transacciones de Spring a propósito — una consola de desarrollo quiere cada sentencia aislada, no
metida en la transacción de un request ajeno. Consecuencia: un datasource **JTA** no está soportado
en esta versión.

## Cosas que conviene saber

- **Seleccionar una asociación `to-one` mete un join implícito.** `SELECT e.nombre, e.departamento
  FROM Empleado e` **no** devuelve las filas cuyo FK es null: Hibernate arma un join interno. Si
  querés todas las filas, usá `LEFT JOIN e.departamento d` explícito o seleccioná
  `e.departamento.id`. No es un comportamiento de la consola, es de HQL.
- **`Tuple.class` es una extensión de Hibernate, no JPQL portable.** La especificación Jakarta
  Persistence dice explícitamente que pasarle otro tipo de resultado a `createQuery(String, Class)`
  no es portable. Se usa como camino preferido porque es lo único que da los alias, y hay un camino
  JPA plano (`Object[]`, sin alias) como fallback: si un proveedor lo rechaza, degrada en vez de
  romper.
- **No hay parámetros con nombre.** Todavía no se pueden completar `:param`, así que las consultas
  van con literales.
- **Con Spring Security puesto**, la página y el endpoint quedan detrás de la cadena de filtros por
  defecto. No hay integración todavía.
- **Sólo Jakarta** (`jakarta.persistence`), o sea Boot 3.x y 4.x. Boot 2.7 usa `javax.persistence`,
  que es binariamente incompatible en el mismo jar: necesitaría un artefacto aparte.
- Funciona con **cero repositorios de Spring Data** (verificado): le alcanza con el
  `EntityManagerFactory`.

## Seguridad

La consola ejecuta **HQL arbitrario** contra el `EntityManager` vivo: puede leer cualquier entidad
y, con las escrituras habilitadas, modificar o borrar datos. Es acceso de administrador a la base.

Viene **encendida por defecto** a propósito (la gracia es tirar el jar y que aparezca la página), así
que la responsabilidad de apagarla es del entorno:

- `hql-console.enabled=false` para desactivarla, o no incluir el jar fuera de desarrollo;
- `hql-console.allow-writes=false` si querés consultar sin riesgo de escribir;
- `hql-console.max-rows` limita el daño de un `from Entidad` sin filtros;
- no la expongas más allá de localhost.

## Compatibilidad con Spring Boot

Este jar se compila contra Spring Boot 3 pero **se ejecuta contra la versión que tenga tu
aplicación**. Eso tiene una consecuencia que no es obvia: referenciar una clase interna de Boot
compila perfecto y explota en runtime, en la app de otro, con `NoClassDefFoundError`.

Pasó en la primera versión. El banner de arranque usaba `ServletWebServerApplicationContext` y
`WebServer`, y Spring Boot 4.0 movió esos paquetes:

| Spring Boot 3 | Spring Boot 4 |
|---|---|
| `org.springframework.boot.web.servlet.context` | `org.springframework.boot.web.server.servlet.context` |
| `org.springframework.boot.web.embedded.tomcat.*` | `org.springframework.boot.tomcat.*` (y `.tomcat.servlet.*`) |
| `org.springframework.boot.web.embedded.jetty.*` | `org.springframework.boot.jetty.*` |
| `org.springframework.boot.web.embedded.undertow.*` | eliminado |

([receta de reubicación de OpenRewrite](https://docs.openrewrite.org/recipes/java/spring/boot4/relocatewebserverclasses))

Peor: como el banner es un `ApplicationRunner`, la excepción **abortaba el arranque entero** de la
aplicación anfitriona. Un mensaje informativo no tiene derecho a hacer eso, y ahora no puede:
`catch(Throwable)` alrededor de todo.

La regla que quedó, y que el build hace cumplir:

> De Spring Boot sólo se tocan las anotaciones de auto-configuración, las de
> `@ConfigurationProperties` y `ApplicationRunner`/`ApplicationArguments`. Para todo lo demás
> —puerto, context-path, servidor web— se leen **propiedades del `Environment`**, no clases.

El puerto sale de `local.server.port` (la misma propiedad que resuelve `@LocalServerPort`), que es
correcta incluso con `server.port=0`, con `server.port` como respaldo.

La tarea `checkBootSurface` del build escanea los `.class` del jar y **falla la compilación** si
alguien reintroduce una referencia a `org.springframework.boot.web.*`, `...tomcat.*`, `...jetty.*`,
`...undertow.*` o `...reactor.*`. Corre como parte de `check`/`build`, así que el error aparece en
la máquina del que lo escribe y no en la del que lo usa.

Verificado en Spring Boot 3.2.5 / Hibernate 6.4.4. En 4.x debería andar (ya no hay ninguna clase
interna referenciada y el guard lo garantiza), pero no está verificado en este workspace.

## Estado

Verificado end-to-end con `verify-demo.ps1`, que compila, levanta el fat jar del demo y corre las
comprobaciones contra una H2 en memoria (Spring Boot 3.2.5, Hibernate 6.4.4, Java 21):

```
.\verify-demo.ps1                                  # 85 PASS / 0 FAIL
.\verify-demo.ps1 -ContextPath /demo -MaxRows 3    # 88 PASS / 0 FAIL
```

Cubre: descubrimiento de la auto-configuración por el `.imports` del jar, la página servida desde
el jar, que el encabezado sólo diga `HQL Console`, headers deducidos y con `AS`, `from Entidad`
aplanado y titulado con los atributos de la clase (y que con `SELECT` o con un join explícito
**no** se aplane), asociaciones perezosas, `NULL`, agregados, resultados vacíos, HQL
inválido, el tope de filas y el `context-path` (incluido que la ruta sin context-path dé 404). De
las sentencias propias: las 4 columnas de `DESC` con el tipo SQL real y el orden de declaración,
`DESC` sin argumentos, `INSERT` con conversión de fecha, `NOW` a `DATE` y a `TIMESTAMP`, enum,
relación por id, campos omitidos en NULL y fallo por `NOT NULL`, y `UPDATE` que sólo toca el SET,
con `NOW`, con `WHERE` compuesto, sin alias, sin `WHERE` (y el aviso de truncado), más los errores
de entidad, atributo y alias mal capitalizados, y el fallback de `INSERT ... SELECT` a HQL.

Las funciones puras del ejecutar se verifican de verdad: el script extrae el bloque marcado en el
HTML **que sirve el jar** y lo corre en Node, con los casos de la selección (sin selección, con
selección y selección invertida) y doce casos del párrafo (cursor en cada párrafo, al final de una
línea, en una línea en blanco, en los bordes, sin líneas en blanco, con CRLF y con el textarea
vacío). Requiere `node` en el PATH; si no está, ese chequeo se saltea. Ojo si editás
`HqlConsolePage.java`: el JS vive en un text block de Java, así que una barra invertida va doble.

Del layout partido se comprueba que la página traiga los dos paneles, el divisor arrastrable, el
ancho variable por CSS, que los resultados vivan en el panel derecho y que el botón quede dentro
del panel izquierdo y después del textarea; de la persistencia, que el texto se guarde, se restituya
y se guarde al tipear, que el ancho también se persista, y que la respuesta venga con
`Cache-Control: no-store`.

Aparte se comprobó a mano el caso difícil del banner: con `--server.port=0` anuncia el puerto real
que le asignó Tomcat y esa URL responde 200 (o sea que `local.server.port` se resuelve bien).

En este workspace hay que pasarle el home de Gradle local:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path ..\.gradle-home).Path
.\gradlew.bat --console=plain build
```

## Estructura

```
hql-console-starter/            el jar que se distribuye (java-library, ~20 KB, sin dependencias)
  build.gradle                  incluye la tarea checkBootSurface (la guarda de compatibilidad)
  autoconfigure/
    HqlConsoleAutoConfiguration   @AutoConfiguration + @ConditionalOn* + los @Bean
    HqlConsoleProperties          hql-console.*
    HqlConsoleBanner              el aviso en el log al arrancar (sólo Environment, nunca clases de Boot)
  engine/
    HqlQueryRunner                despacho, transacción, aplanado de filas, mapeo de celdas, headers
    Statement + StatementParser   las sentencias propias de la consola (sólo sintaxis)
    AttributeBinder               ruta -> tipo destino -> valor, y la asignación por reflection
    EntityDescriber               DESC (metamodelo + metadata real de la base)
    Mapping                       nombres de tabla y columna, orden de declaración, lectura por reflexión
    Text                          escaneo de la sentencia (paréntesis y comillas)
    HqlResult                     el JSON que sale
  web/
    HqlConsoleController          GET {path} y POST {path}/api/execute
    HqlConsolePage                el HTML+CSS+JS, en un text block (ojo: barras invertidas dobles)
hql-console-demo/               aplicación de ejemplo: Empleado/Departamento + H2, sin config
verify-demo.ps1                 la verificación end-to-end
```

## Roadmap

- Aplanar también un join explícito (`from Libro l join l.autor a`): hoy muestra `Libro#1 | Autor#1`.
- `INSERT` de varias filas (hoy es de una: la sintaxis no tiene separador de filas).
- `DELETE Libro li WHERE ...` con la misma semántica que el `UPDATE` (cargar y `em.remove()`).
- Aplanar `@Embedded` dentro de `from <Entidad>`.
- Columnas extra en `DESC`: `NULL`, PK y largo/precisión (la metadata ya las trae).
- Parámetros con nombre (`:param`) con un panel de valores.
- Historial de consultas y export a CSV.
- Timeout por consulta.
- Selector de unidad de persistencia para apps con varias.
- Integración con Spring Security (rol propio) y default de escrituras en `false`.
- Tests automatizados (JUnit + Testcontainers) en vez del script de verificación.
- Artefacto aparte para Spring Boot 2.7 (`javax.persistence`).

## Licencia

MIT.
