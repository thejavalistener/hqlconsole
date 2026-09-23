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

## Descargar los jars

En [Releases](https://github.com/thejavalistener/hqlconsole/releases) hay un jar por versión:

| Asset | Qué es |
|---|---|
| `hql-console-demo.jar` | fat jar ejecutable de la demo: `java -jar hql-console-demo.jar` y la consola queda en `http://localhost:18080/hqlconsole` (Tomcat, Hibernate y H2 adentro) |
| `hql-console-starter-<version>.jar` | la consola sola, para usar como dependencia |
| `hql-console-starter-<version>-sources.jar` | las fuentes |
| `SHA256SUMS` | el sha256 de los tres |

Los jars **no** están versionados en el repositorio: se construyen en cada release. El workflow
`.github/workflows/release.yml` se dispara con un tag `v*`, comprueba que el tag coincida con la
versión que declara `build.gradle` (le pregunta con `gradlew -q printVersion`; si no coincide, corta
antes de publicar nada) y adjunta los artefactos. Para cortar una release:

```powershell
# primero: subí la versión en build.gradle y commiteá
git tag -a v0.1.0 -m "0.1.0"
git push origin v0.1.0
```

> Para consumir el starter como dependencia (`implementation ...`) está **JitPack**: clona el tag,
> lo compila y sirve el artefacto a demanda, sin subir nada a mano. Bajar el jar del release y
> meterlo con `files(...)` funciona, pero perdés las dependencias transitivas y las actualizaciones.

## Uso

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.thejavalistener.hqlconsole:hql-console-starter:v0.1.3'
}
```

Eso es todo. No hay `@Import`, ni `@ComponentScan`, ni `@EnableHqlConsole`, ni una línea de
configuración: el jar trae su auto-configuración declarada en
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` y Spring Boot la
descubre sola.

La coordenada tiene dos particularidades que se pagan una vez y después se olvidan:

- **El groupId incluye el repo**: `com.github.<usuario>.<repo>`, o sea
  `com.github.thejavalistener.hqlconsole`. Es la convención de JitPack para builds multi-módulo,
  donde cada módulo se publica por separado con el nombre del módulo como artifactId. La forma
  corta de un solo módulo (`com.github.usuario:repo`) no aplica acá.
- **La versión es el tag**, no el `version` de `build.gradle`: `v0.1.0`. Es el mismo tag que dispara
  el release de GitHub, así que hay una sola cosa que recordar.

La primera vez que alguien pide el artefacto, JitPack compila el tag (tarda unos minutos) y lo deja
cacheado; de ahí en adelante la descarga es instantánea. El log de ese build está en
`https://jitpack.io/com/github/thejavalistener/hqlconsole/v0.1.0/build.log`, y el estado se puede
ver en `https://jitpack.io/#thejavalistener/hqlconsole`.

El `jitpack.yml` de la raíz existe sólo para fijar el JDK: JitPack compila con Java 8 por defecto y
este build necesita 17. El comando de build es el default de JitPack para Gradle
(`gradlew build publishToMavenLocal`), que además de publicar el starter corre `checkBootSurface`.

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
| `allow-writes` | `true` | Habilita las sentencias de escritura (`insert`, `update`, `delete`), incluidas las de un lote. |
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
- La selección o el párrafo pueden tener **varias sentencias separadas por `;`**: se ejecutan juntas,
  como un lote (ver [Varias filas de una vez](#varias-filas-de-una-vez)).

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

- **Tres paneles.** A la izquierda del todo está la **lista de entidades** (angosta: sólo los
  nombres), después el **editor** y después los **resultados** (la grilla, el resumen y el JSON
  crudo). El botón **Ejecutar** vive dentro del panel del editor, debajo del textarea y alineado a la
  derecha; al lado está el aviso de qué se va a ejecutar. El divisor del medio se arrastra con el
  mouse; con el foco puesto en él, las flechas lo mueven de a 2% (con `Shift`, de a 10%),
  `Inicio`/`Fin` van a los extremos y el doble clic vuelve a 50/50. El ancho elegido se recuerda. Por
  debajo de 720 px de ancho los paneles se apilan, el divisor desaparece y la lista de entidades se
  esconde.
- **La lista de entidades es un atajo.** Se pide sola al abrir la página (es el mismo `DESC` sin
  argumentos) y **cada nombre es clickeable**: equivale a escribir `DESC <Entidad>` y ejecutar, sin
  pisar lo que tengas en el editor. La entidad que estás mirando queda marcada, y la lista se
  **contrae y se expande** con el botoncito de su cabecera; el estado se recuerda en el navegador
  igual que el ancho del editor.
- **Dejá el mouse sobre una entidad y sale el menú solo** (al segundo), o **abrilo ya con el botón
  derecho**. Tiene las tres acciones y cada rótulo muestra la sentencia que genera:
  - **`DESC Entidad`**: el mismo atajo que el clic.
  - **`SELECT * FROM Entidad`**: corre `SELECT * FROM Entidad LIMIT 100` sin pasar por el editor. El
    `100` es porque un clic no debería traerte una tabla entera; si el tope global `max-rows` es
    menor, gana ese y el resultado se marca como truncado.
  - **`INSERT INTO Entidad`**: arma un `INSERT` de ejemplo y lo **escribe en el editor** (no lo
    ejecuta), **abajo del párrafo donde está el cursor** —o **al principio de todo** si el cursor está
    en una línea en blanco por encima de lo escrito— sin pisar lo que tenías y **sin mover el
    scroll**, así quedás mirando donde estabas. La sentencia insertada queda **seleccionada**, que es
    lo que hace evidente dónde apareció; la contra es que la próxima tecla la reemplaza, así que para
    completarla hay que hacer clic adentro. Excluye el `id` —lo genera la base— y pone un valor
    acorde al tipo de cada columna: `999` para los números, `'999'` para los textos, `'2024-01-01'`
    para las fechas, `NOW` para los timestamps y `false` para los booleanos. Las relaciones van **por
    el id, sin comillas** cuando ese id es numérico (lo dice la columna `RELACION` del `DESC`).
  - El **clic sigue haciendo el `DESC`**, que es el atajo rápido: el menú es para lo demás. Al hacer
    clic el menú se **cierra y no vuelve** hasta que saques el mouse y vuelvas a entrar.
  - El menú se **cancela** si sacás el mouse antes del segundo, y **se cierra si alejás el mouse**:
    para eso se mide la distancia al ítem y al menú con una tolerancia de unos píxeles, así el menú
    **no se cierra mientras cruzás** del ítem al menú (que es lo que pasaba yendo lento, con el
    divisor de paneles justo en el medio). También se cierra con `Escape`, con la rueda, al salir de
    la ventana o si cambiás el tamaño.
- **El editor no envuelve las líneas.** Una línea larga **scrollea en horizontal** en vez de partirse,
  que es lo que se espera de un editor de código: la sentencia se lee como la escribiste.
- **El párrafo que ejecutaste queda pintado.** Al correr sin selección, la consola selecciona el
  párrafo que acaba de ejecutar: se ve de un vistazo qué corrió, y el próximo `Ctrl+Enter` corre
  exactamente lo mismo sin volver a apuntar con el cursor. Si ya tenías algo seleccionado, tu
  selección se respeta y no se toca.
- **El foco arranca en el editor.** Al abrir la consola el cursor ya está en el textarea, en el
  carácter 0: uno viene a escribir acá, así que no hace falta hacer clic primero.
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

> **El case de los atributos lo resuelve Hibernate, y es tolerante.** `SELECT l.id`, `SELECT l.ID`,
> `SELECT l.Id` y `WHERE l.ID = 1` **funcionan los cuatro**: el motor resuelve los nombres de atributo
> sin distinguir mayúsculas (un atributo inexistente sí falla, con `UnknownPathException`). Es una
> diferencia real con las sentencias propias de la consola, donde el `INSERT` y el `UPDATE` sí exigen
> el case exacto (`li.TITULO` da error y sugiere `titulo`). Volver estricto también el `SELECT` sería
> posible, pero requiere escanear el HQL y resolver cada `alias.atributo` contra el metamodelo, y se
> decidió no hacerlo: se documenta el comportamiento en vez de agregar un análisis sintáctico nuevo.

Y si omitís el `SELECT` y escribís sólo el `from`, las filas salen con **todas las columnas planas**
de la entidad, en vez de una sola columna con `Libro#1`:

```sql
from Libro                           -- id | titulo | fechaPublicacion | ... | autor | ...
from Libro l where l.precio > 10000  -- idem, filtrado
SELECT * FROM Libro                  -- exactamente lo mismo que "from Libro"
SELECT * FROM Libro l WHERE l.precio > 10000 ORDER BY l.id LIMIT 10
```

- **`SELECT * FROM <Entidad>` es equivalente a `FROM <Entidad>`.** HQL no acepta el `*` (Hibernate
  tira `SyntaxException`), pero es lo que todos escriben: la consola lo traduce a la forma aplanada
  en vez de rechazarlo. Aplica la misma regla que el `from` pelado, así que con `WHERE`, `ORDER BY`,
  `LIMIT` y alias se comporta igual. Un `SELECT` explícito que **no** sea `*` no se aplana: ahí se
  devuelve exactamente lo que pediste.
- **`LIMIT n` al final.** Cualquier consulta puede terminar en `LIMIT n` (`from`, `SELECT`, con
  `ORDER BY`, lo que sea) y se devuelven **n filas como mucho**. La sentencia puede ser larga: el
  `LIMIT` va al final y nada más que al final. Se aplica con `setMaxResults` (el *maxRows* de JDBC),
  que es lo que corta la cantidad de filas; `fetchSize` no sirve para esto (sólo insinúa de a cuántas
  traer por viaje). Si el tope global `max-rows` es **menor** que el `LIMIT`, gana el tope y el
  resultado se marca como truncado. Un `limit` que no es la cláusula del final no se toca: adentro de
  un literal (`LIKE '%limit 5%'`) o de una subconsulta es parte de la expresión.
- Las columnas salen en el orden de declaración de la entidad y **tituladas con el nombre del
  atributo de la clase** (`fechaPublicacion`, `autor`), no con el nombre físico de la tabla. Son los
  nombres que escribiste en la entidad y los que podés volver a escribir en un HQL.
- El nombre físico sigue estando en `DESC`, en la columna `CAMPO`: `DESC Libro` es el mapa de lo que
  te devuelve `from Libro`, y sus títulos son exactamente su columna `ATRIBUTO`.
- **Click en el header de una columna para ordenar.** El primer click ordena ascendente por esa
  columna, el segundo descendente, y así alterna (`▲` / `▼` marcan cuál está activa). Es del lado del
  cliente y **sólo sobre las filas que se ven**: no vuelve a consultar la base. El orden que pidió tu
  sentencia es el punto de partida y no se pierde: cada click vuelve a ordenar desde las filas
  originales, así que cambiar de columna no va acumulando recortes.
  - El criterio de comparación sale del **tipo de cada columna**, que ahora viaja en el JSON
    (`types`): `NUMERO` ordena numéricamente (9 antes que 10), `FECHA` cronológicamente, `TEXTO`
    alfabéticamente (10 antes que 9, que es lo que significa ordenar texto) y `BOOLEANO` false antes
    que true.
  - Los **NULL van siempre al final**, también en descendente: si no, ordenar al revés arrancaría con
    una pantalla llena de NULL.
  - Funciona también en el panel de detalle, y cada grilla ordena por su cuenta.
- Una relación `to-one` se muestra como **el id de la FK** (`autor` → `1`), sin inicializar el
  proxy ni traer la entidad relacionada.
- Las colecciones (`@OneToMany`) no se muestran: no son campos planos.
- Un `join fetch` también se aplana, porque la fila sigue siendo sólo la entidad. Un **join
  explícito** (`from Libro l join l.autor a`) no: la fila tiene dos raíces y sale como
  `Libro#1 | Autor#1`. Con `SELECT` explícito tampoco se aplana, porque ahí se devuelve exactamente
  lo que se pidió.

### SQL nativo (sólo lectura)

La solapa **SQL** ejecuta consultas SQL nativas contra la misma conexión de la aplicación. Sólo
acepta una sentencia `SELECT` por vez: `INSERT`, `UPDATE`, `DELETE`, DDL y CTE (`WITH`) se rechazan
antes de tocar la base. Los comentarios `//`, `#` y `--` funcionan igual que en HQL, y el tope
`hql-console.max-rows` también se aplica y avisa si el resultado quedó truncado.

El panel izquierdo muestra las tablas y vistas del esquema, incluidas las que no tienen entidad
mapeada; permite filtrarlas por nombre y tipo. Un clic ejecuta `SELECT * FROM <tabla> LIMIT 100`.
En esta solapa, `DESC` lista `TABLA | TIPO | ES_ENTIDAD` y `DESC <tabla>` muestra
`CAMPO | TIPO SQL | NULO | PK | FK`. No hay navegación de relaciones ni generación de INSERT.

### Las tres sentencias propias de la consola

Hibernate no las conoce. Son éstas:

### `INSERT`

Tres formatos, los tres equivalentes:

```sql
-- 1) con alias: el más explícito, y el que sugiere el mensaje de error
INSERT INTO Libro li VALUES li.titulo='Las mil y una noches',
                            li.fechaPublicacion='1994-11-23',
                            li.fechaAlta=NOW

-- 2) sin alias: el campo va pelado
INSERT INTO Libro VALUES titulo='Las mil y una noches', fechaPublicacion='1994-11-23'

-- 3) estilo SQL: primero las columnas, después los valores en el mismo orden
INSERT INTO Libro (titulo, fechaPublicacion, fechaAlta)
     VALUES ('Las mil y una noches', '1994-11-23', NOW)
```

- En el (3) los nombres entre paréntesis son **atributos de la clase** (`fechaPublicacion`), no
  columnas físicas, y la cantidad de columnas tiene que coincidir con la de valores. Como los valores
  van **por posición**, el orden es toda la información: si te equivocás entre dos columnas del mismo
  tipo, entra sin chistar. Cuando el INSERT es uno solo y lo escribís a mano, el (1) es más seguro
  porque el nombre viaja pegado al valor.
- El **id se genera solo** si es `@GeneratedValue`. La consola te lo devuelve en el mensaje:
  `Insertado Libro#7`.
- Los **campos que no nombrás quedan en NULL**, o falla la sentencia con el error de la base si la
  columna no los acepta. No se inventan valores.
- Una **relación se asigna por el id**: `li.autor=5` y `li.autor.id=5` son equivalentes y resuelven
  la FK sin que tengas el objeto. En el formato (3) vale igual, porque la columna dice el tipo:
  `INSERT INTO Libro (titulo, autor) VALUES ('Uno', 1)` guarda la FK al autor 1.
- Al terminar avisa con un **alert**: *"Se insertó 1 fila"*.

### Varias filas de una vez

Varias sentencias separadas por `;` se ejecutan como un **lote**: una sola transacción (o entran todas
o no entra ninguna) y **un solo alert** al final.

```sql
INSERT INTO Libro (titulo, precio) VALUES ('Uno', 100);
INSERT INTO Libro li VALUES li.titulo='Dos', li.precio=200;
INSERT INTO Libro VALUES titulo='Tres', precio=300
```

→ *"Se insertaron 3 filas en 3 sentencias"*.

- Los tres formatos se pueden **mezclar** en el mismo lote, y son la misma cosa para el motor.
- Se tolera el `;` del final y los `;;` de más. Un `;` dentro de un texto (`titulo='a;b'`) **no**
  corta la sentencia.
- **Un lote es sólo de INSERT**: sirve para dar de alta datos de prueba. Si alguna sentencia no lo es,
  el error dice cuál: *"La sentencia 2 de 4 no es un INSERT..."*.
- Si una falla, el error dice **cuál** y no se inserta ninguna.
- El lote es **atómico**: se valida entero antes de tocar la base y va en una sola transacción.
- Con **una sola** sentencia se ejecuta como siempre (y el `;` final se descarta, en vez de quedar
  dentro del último valor).
- Ojo con las **conversiones**: adentro del lote, cada sentencia entra por el camino de la consola, así
  que `NOW`, los enums y las relaciones por id funcionan igual que sueltas.

> **El multi-fila de SQL no es gramática de la consola.** `VALUES (1,2), (3,4)` lo entiende Hibernate
> como HQL y lo ejecuta igual, pero por otro camino: es un *bulk*, así que **no dispara
> `@PrePersist`**, no valida y **no conoce `NOW`**. Para cargar datos, usá el lote con `;`.


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

### La confirmación antes de commitear

`UPDATE` y `DELETE` **no se ejecutan de una**: primero la consola los corre en seco (*dry-run*), cuenta
cuántas filas tocaría y te pregunta.

```
Escribís:  DELETE FROM Empleado e WHERE e.salario < 100000
Ctrl+Enter
   → la consola lo ejecuta, cuenta 4 filas y tira todo atrás (rollback)
   → "Se van a borrar 4 filas. ¿Confirmás?"    [Aceptar] [Cancelar]
   → si aceptás, recién ahí se commitea
```

El número es la alarma: si esperabas borrar **una** fila y el aviso dice 4 porque te comiste el
`WHERE`, cancelás y te salvaste. Si el tope de filas (`max-rows`) truncó el `UPDATE`, el aviso también
lo dice.

- **Es automático**: no hay que activar nada. La página lo hace sola con `UPDATE` y `DELETE`. Los
  `INSERT` sólo avisan al terminar, porque ahí no hay nada que confirmar.
- **No queda nada colgado**: el dry-run termina en `rollback` y cierra su transacción, así que
  mientras el diálogo está en pantalla no hay filas bloqueadas ni conexiones tomadas. Si cerrás la
  pestaña en vez de contestar, no hay nada que limpiar del lado del servidor.
- **El número es exacto**, no una estimación: sale de haber ejecutado la sentencia de verdad.
- **La contra, que conviene saber**: la sentencia corre dos veces (una descartada y una de verdad).
  Los `@PreUpdate` / `@PreRemove` que hagan algo **por fuera** de la transacción —mandar un mail,
  escribir una auditoría en otra conexión— lo hacen dos veces.
- Con `allow-writes=false` no hay dry-run que valga: la sentencia se rechaza antes de ejecutarse.

### Comentarios

Las líneas que empiezan con `//`, `#` o `--` son comentarios, y **se excluyen antes de ejecutar**:

```sql
// datos de prueba
INSERT INTO Libro (titulo, precio) VALUES ('Uno', 100);   -- y este también

SELECT e.id,   # el id
       e.nombre
  FROM Empleado e
```

- **No se le mandan a Hibernate**: se sacan antes de partir por `;` y de parsear. Es lo que hace que
  un `;` adentro de un comentario **no parta la sentencia**, y que un `where` comentado no se
  confunda con el `WHERE` de verdad.
- Cada comentario se reemplaza por **un espacio**, no se borra: si se borrara, dos tokens separados
  por un comentario quedarían pegados y la sentencia cambiaría de significado.
- Un `--` o un `#` **adentro de un literal es texto**, no un comentario: `'a--b'` se guarda tal cual.
- Tres estilos, y los tres terminan en el fin de línea. **No hay comentarios de bloque**.
- Los comentarios **cuentan como parte del párrafo**: si escribís un comentario en su propia línea
  arriba de la sentencia (sin línea en blanco en el medio), `Ctrl+Enter` ejecuta las dos cosas y la
  sentencia corre igual, porque el comentario se descarta al ejecutar.
- Si lo único que hay es un comentario, la consola avisa: *"La sentencia quedó vacía: sólo tenía
  comentarios."*

> **Todavía no tienen color.** Un `<textarea>` no puede pintar parte del texto: para eso hace falta el
> truco del overlay (un `<pre>` pintado detrás y el textarea transparente encima), que se lleva mal
> con el scroll horizontal. Queda para más adelante.

### `DESC <Entidad>`

```sql
DESC Libro
```

| ATRIBUTO | TIPO JAVA | CAMPO | TIPO SQL |
|---|---|---|---|
| id* | Long | ID | BIGINT |
| titulo | String | TITULO | CHARACTER VARYING |
| fechaPublicacion | LocalDate | FECHA_PUBLICACION | DATE |
| autor | Autor | ID_AUTOR | BIGINT |

- El **`*` marca el atributo que es `@Id`**, o sea el que genera la base y el que conviene excluir de
  un `INSERT` a mano. Va pegado al nombre, en la misma columna, para no agregar una columna nueva.
- Primero lo que escribís en una sentencia —`ATRIBUTO` y su `TIPO JAVA`— y después lo que existe en
  la base: la columna física (`CAMPO`) y su tipo SQL (`TIPO SQL`).
- `CAMPO` y `TIPO SQL` son los **reales de la base**, leídos por JDBC (`DatabaseMetaData`). Sin
  `DataSource` en el contexto se derivan del mapping en vez de fallar.
- En una relación `to-one`, `CAMPO` es la columna de la FK y `ATRIBUTO`/`TIPO JAVA` son la relación y
  su entidad: `autor | Autor | ID_AUTOR | BIGINT`.
- El orden es el **de declaración de los campos en la entidad**. Una colección (`@OneToMany`) no
  tiene columna en esta tabla y sale con `-`.
- **Las filas de una relación son clickeables**: `autor | Autor | ID_AUTOR | BIGINT` abre el detalle
  de `Autor` en el panel de abajo. Y desde ahí se puede seguir encadenando (`Autor` → su relación →
  …), porque la detección es la misma columna `TIPO JAVA`.

### Mayúsculas: tablas y columnas, no atributos

La consola usa una convención para que **la misma aplicación se vea igual en cualquier base**:

| Qué | Cómo se muestra | Ejemplo |
|---|---|---|
| Tablas y columnas (lo que existe en la base) | **MAYÚSCULAS**, si el nombre viene en un solo caso | `LIBROS`, `FECHA_PUBLICACION` |
| …con mayúsculas mezcladas | **tal cual** | `EtiquetaRara` |
| Atributos y clases (lo que está en el código) | **tal cual**, case sensitive | `fechaPublicacion`, `Libro` |

El corte no es capricho. H2 guarda los identificadores sin comillas en mayúsculas y **Postgres en
minúsculas**, así que sin normalizar la grilla de `DESC` cambiaría según dónde corra. Y un nombre con
mayúsculas mezcladas, en SQL, **sólo existe si está entrecomillado**: pasarlo a mayúsculas apuntaría
a otro identificador. La metadata de JDBC no dice si estaba entrecomillado, pero las mayúsculas
mezcladas lo delatan, así que esos se muestran sin tocar.

### `DESC` (sin argumentos)

```sql
DESC
```

Lista todas las entidades del contexto, con su tabla y su cantidad de columnas:

| ENTIDAD | TABLA | CAMPOS |
|---|---|---|
| Autor | AUTORES | 2 |
| Departamento | DEPARTAMENTOS | 2 |
| Empleado | EMPLEADOS | 5 |
| Etiqueta | EtiquetaRara | 2 |
| Libro | LIBROS | 9 |

**Cada fila de esa lista es clickeable.** Al hacer clic, el panel de resultados se parte en dos y
abajo aparece el detalle de esa entidad — el mismo `DESC <Entidad>` de arriba, pero sin tener que
escribirlo:

```
┌─ resultado ───────────────────────────────────┐
│ ENTIDAD      | TABLA         | CAMPOS         │   ← la lista
│ Autor        | autores       | 2              │
│ Libro        | libros        | 9              │   ← clic acá
├───────────────────────────────────────────────┤   ← este divisor se arrastra
│ Detalle de Libro                              │
│ ATRIBUTO | TIPO JAVA | CAMPO | TIPO SQL       │   ← el detalle
│ id       | Long      | ID    | BIGINT         │
└───────────────────────────────────────────────┘
```

- El divisor del medio se arrastra con el mouse; con el foco en él, las flechas lo mueven (2%, 10%
  con `Shift`), `Inicio`/`Fin` van a los extremos y el doble clic vuelve a 45%. El alto se recuerda.
- La fila elegida queda marcada, así se ve de dónde salió el detalle.
- Es **sólo la lista de `DESC` sin argumentos** la que se puede clickear: la página sabe qué
  ejecutaste, así que una grilla cualquiera con una columna llamada `ENTIDAD` no se vuelve clickeable
  por accidente.
- Se manda el nombre de la **entidad** (columna `ENTIDAD`), no el de la tabla: `DESC` espera `Libro`,
  no `libros`.
- **La misma lista está siempre a mano en el panel lateral izquierdo**, sin tener que ejecutar `DESC`:
  cada nombre de ahí hace el `DESC` de esa entidad y marca la entidad elegida. El panel se contrae a
  una franja con el botón para volver a abrirlo.
- Es una **lectura**: funciona aunque la consola esté en `allow-writes=false`, y no puede cambiar
  nada. Si el detalle falla, el error aparece **abajo** y la lista de arriba queda intacta.
- **Cada sentencia nueva resetea todo el panel derecho**: si estaba partido, se cierra el detalle, y
  se borra lo que hubiera quedado de la ejecución anterior (la grilla, el detalle, el título y el
  JSON crudo). Así lo que ves siempre es el resultado de la última sentencia, nunca una mezcla. El
  reset también corre cuando la sentencia falla. Lo único que **no** borra es una confirmación
  cancelada: si cancelás un `DELETE`, el resultado que estabas mirando queda donde estaba, porque no
  se ejecutó nada.

**Y desde el detalle se navegan las relaciones.** Los atributos que apuntan a otra entidad
(`@ManyToOne`) salen en la grilla con la entidad destino en la columna `TIPO JAVA`, y esa fila
también es clickeable. La cadena entera:

```
DESC              →  la lista de entidades              (clic en una fila)
  ↓
DESC Libro        →  sus campos y atributos             (clic en el atributo autor)
  ↓
detalle de Autor  →  abajo, en el mismo panel           (y de ahí se sigue)
```

Para saber cuál `TIPO JAVA` es una entidad y cuál un tipo común (`String`, `Long`, `LocalDate`), la
página pide una vez la lista de entidades —el mismo `DESC` de siempre— y compara. Sale de la misma
fuente de verdad que todo lo demás, no hay heurísticas sobre el nombre ni cambios en el motor. Si esa
lista no se puede obtener, la grilla simplemente queda sin clickear.

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
.\verify-demo.ps1                                  # 236 PASS / 0 FAIL
.\verify-demo.ps1 -ContextPath /demo -MaxRows 3    # 244 PASS / 0 FAIL
```

Cubre: descubrimiento de la auto-configuración por el `.imports` del jar, la página servida desde
el jar, que el encabezado sólo diga `HQL Console`, headers deducidos y con `AS`, `from Entidad`
aplanado y titulado con los atributos de la clase (y que con `SELECT` o con un join explícito
**no** se aplane), `SELECT * FROM Entidad` (equivalente al `from`, con alias, `WHERE` y `ORDER BY`),
`LIMIT n` al final (que corte de verdad, que no llegue a Hibernate, que un `limit` dentro de un
literal no se confunda con la cláusula, y los errores de `limit` sin número, con texto y con 0),
asociaciones perezosas, `NULL`, agregados, resultados vacíos, HQL
inválido, el tope de filas (y que un `LIMIT` mayor que el tope quede recortado por el tope) y el
`context-path` (incluido que la ruta sin context-path dé 404). De
las sentencias propias: las 4 columnas de `DESC` en su orden (atributo y tipo Java primero, campo y
tipo SQL después) con el tipo SQL real y el orden de declaración,
`DESC` sin argumentos, los **tres formatos de `INSERT`** (con alias, sin alias y posicional) con la
conversión de fecha, `NOW` a `DATE` y a `TIMESTAMP`, enum, relación por id **también en el formato
posicional**, campos omitidos en NULL,
fallo por `NOT NULL`, columna inexistente, columnas y valores que no coinciden, paréntesis sin
cerrar, los **lotes** con `;` (filas y sentencias, formatos mezclados, `;` final y `;;`, `;` dentro
de un literal, atomicidad comprobada, y el rechazo de lo que no es INSERT), el **dry-run** de
`UPDATE` y `DELETE` (que cuenta bien, que **no** cambia los datos, que al confirmar sí los cambia, y
que después la consola sigue respondiendo), y `UPDATE` que sólo toca el SET, con `NOW`, con `WHERE`
compuesto, sin alias, sin `WHERE` (y el aviso de truncado), más los errores de entidad, atributo y
alias mal capitalizados, y el fallback de `INSERT ... SELECT` a HQL.

De la **convención de mayúsculas** se comprueba end-to-end: que una tabla en un solo caso salga en
mayúsculas (`LIBROS`), que una con mayúsculas mezcladas salga tal cual (`EtiquetaRara`) y que la
entidad salga como la clase (`Etiqueta`). Para poder ejercitar el caso mezclado —H2 nunca lo produce,
porque todo lo que crea sin comillas lo pasa a mayúsculas— el demo tiene a propósito la entidad
`Etiqueta`, mapeada a la tabla `EtiquetaRara`.

Del **detalle de `DESC`** se comprueba que la página traiga el split horizontal, que el divisor del
detalle tenga su persistencia, que la lista clickeable salga de la columna `ENTIDAD` y sólo de un
`DESC` sin argumentos, que el clic pida el `DESC` de esa entidad, que el detalle maneje su propio
error sin tocar la lista de arriba, y que otro resultado cierre el detalle. De la **navegación por
relaciones**: que toda ejecución nueva cierre el panel de abajo, que el `DESC` de una entidad cablee
los atributos clickeables por la columna `TIPO JAVA`, que la lista de entidades se pida sola si no se
tiene, que el panel de abajo también encadene, y que la fila elegida se marque y se desmarque.
**El clic y el layout en sí no los ve ningún test**: eso hay que mirarlo en el navegador.

Del **reset del panel derecho** se comprueba que corra antes de mostrar cada resultado, que cierre el
detalle y borre lo anterior, que también corra cuando la sentencia falla, y que el CSS respete el
atributo `hidden`. Ese último es un bug que apareció al usarlo: un panel con `display:flex` en su
propia regla de CSS se quedaba **visible** aunque el JS le pusiera `hidden`, porque el `id` pesa más
en la cascada que la regla del navegador. Ahora `[hidden] { display:none !important }` lo garantiza
para cualquier panel de la página.

Las funciones puras del ejecutar se verifican de verdad: el script extrae el bloque marcado en el
HTML **que sirve el jar** y lo corre en Node, con los casos de la selección (sin selección, con
selección y selección invertida), doce casos del párrafo (cursor en cada párrafo, al final de una
línea, en una línea en blanco, en los bordes, sin líneas en blanco, con CRLF y con el textarea
vacío), el alert del INSERT, el texto de la confirmación, el reconocimiento del `DESC` y el nombre
de entidad que usa el panel lateral (`entidadDeDesc`). Requiere `node` en el PATH; si no está, ese
chequeo se saltea. Ojo si editás `HqlConsolePage.java`: el JS vive en un text block de Java, así que
una barra invertida va doble.

Del **panel lateral de entidades** se comprueba que la página traiga el panel, su control de
contraer/expandir con la persistencia, que sea angosto y sólo muestre los nombres, que la lista salga
del `DESC` sin argumentos, que el clic ejecute el `DESC` de esa entidad **sin pisar el editor**, que
la entidad elegida se marque y que la lista se pida sola al abrir la página.

Del layout partido se comprueba que la página traiga los paneles, el divisor arrastrable, el
ancho variable por CSS, que los resultados vivan en el panel derecho y que el botón quede dentro
del panel izquierdo y después del textarea; de la persistencia, que el texto se guarde, se restituya
y se guarde al tipear, que el ancho también se persista, y que la respuesta venga con
`Cache-Control: no-store`.

También se comprueba `gradlew -q printVersion`: el release le pregunta la versión al build con esa
tarea, así que si dejara de imprimirla el release saldría con los jars titulados con otro número. El
chequeo la compara contra el nombre real del jar del starter. El workflow de release en sí no se
puede correr desde acá (necesita un runner de GitHub); lo que sí se validó es su YAML y que la
versión que consume sea la del build.

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
    HqlQueryRunner                despacho, transacción, lotes, aplanado de filas, mapeo de celdas, headers
    Statement + StatementParser   las sentencias propias de la consola (sólo sintaxis)
    AttributeBinder               ruta -> tipo destino -> valor, y la asignación por reflection
    EntityDescriber               DESC (metamodelo + metadata real de la base)
    Mapping                       nombres de tabla y columna, orden de declaración, lectura por reflexión
    Text                          escaneo de la sentencia (paréntesis y comillas)
    HqlResult                     el JSON que sale
  web/
    HqlConsoleController          GET {path} y POST {path}/api/execute
    HqlConsolePage                el HTML+CSS+JS, en un text block (ojo: barras invertidas dobles)
hql-console-demo/               aplicación de ejemplo: Empleado/Departamento/Libro + H2, sin config
                                (Etiqueta -> EtiquetaRara está sólo para probar las mayúsculas)
verify-demo.ps1                 la verificación end-to-end
.github/workflows/release.yml   tag v* -> construye y publica el release con los jars
jitpack.yml                     JitPack: fija el JDK 17 con el que se compila cada tag
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
