# Features acordadas: registro de decisiones

Este archivo es el registro de las features que se acordaron para la consola, con el **por qué** de
cada decisión y las **trampas** que aparecieron. Está escrito para que cualquiera que retome el
trabajo (una sesión nueva, otro modelo) no tenga que adivinar: la documentación de usuario está en el
`README.md` y la verificación en `verify-demo.ps1`.

## Para retomar el trabajo (leer primero)

- **El repositorio es este**: `D:\vscode\Workspace\hqlconsole`, y es el único clon. Hubo otro en
  `C:\Users\pablo\Documents\DeepSeekHardnes\hqlconsole` (una copia de trabajo del agente) que se
  descartó: no lo recrees, y no trabajes sobre una copia, porque las dos se desincronizan y se pierde
  el hilo. El remote es `github.com/thejavalistener/hqlconsole`.
- **El workspace de la sesión tiene que ser `D:\vscode\Workspace`** (la carpeta padre de este repo).
  Con el workspace en otro lado, cada escritura en el proyecto pide aprobación; con ese, no.
- **`verify-demo.ps1` necesita acceso completo**, no por la escritura sino porque Gradle forkea su
  daemon capturando la salida por pipe y el sandbox confinado lo deniega. Se escala una vez por
  corrida y listo.
- **El push y los tags los hace el dueño del repo**, no el agente.

### Estado del release (importante al publicar)

- Lo último **publicado** es **`v0.1.3`** (tag, release de GitHub con los jars, y artefacto en
  JitPack). Ese tag tiene las features #1 a #5.
- La feature **#6 (ordenar por click en el header) está commiteada pero SIN publicar**: `HEAD` está
  por delante de `v0.1.3` y `build.gradle` sigue en `0.1.3`.
- Para publicar #6 hay que **subir la versión en `build.gradle`** (`version = ... ?: '0.1.4'`) y
  taggear `v0.1.4`: el workflow `.github/workflows/release.yml` **compara el tag con `printVersion`
  y corta si no coinciden**.
- Ojo con esto: el `README.md` de la raíz ya documenta #6 y dice 183/190 PASS, o sea que **describe
  el commit sin publicar**, no el `v0.1.3` que está en GitHub. Es a propósito, pero conviene saberlo.

## Estado

| Feature | Estado |
|---|---|
| #1 `DESC` con `ATRIBUTO` y `TIPO JAVA` primero | **hecho** |
| #2 `INSERT INTO Entidad (campoRel1, campoRel2) VALUES (1, 1)` | **ya funcionaba**; ahora tiene test |
| #3 `SELECT * FROM Entidad` equivalente a `FROM Entidad` | **hecho** |
| #4 `LIMIT n` al final de las consultas | **hecho** |
| #5 panel lateral de entidades, colapsable | **hecho** |
| #6 ordenar la grilla clickeando el header de una columna | **hecho** |
| #7 editor con scroll horizontal (sin wrap) | **hecho** |
| #8 el párrafo ejecutado queda seleccionado | **hecho** |
| #9 `*` en el atributo `@Id` de `DESC` | **hecho** |
| #10 menú contextual de una entidad (INSERT / SELECT *) | **hecho** |
| #11 comentarios `//`, `#`, `--` sin color | **hecho** |
| #12 menú de la entidad por hover (1s) con DESC / SELECT / INSERT | **hecho** |
| #13 el header no cambia de tamaño al pasar el mouse | **hecho** |
| #14 case estricto de atributos en los SELECT | **descartado a propósito**; documentado |
| #15 solapa SQL nativa de sólo lectura | **hecho** |

## #15 — Solapa SQL nativa de sólo lectura

- Hay dos editores persistentes: HQL conserva `hql-console.consulta`; SQL usa
  `hql-console.consulta-sql`; la solapa activa queda en `hql-console.solapa`.
- El backend usa una lista blanca: en SQL sólo llega al motor una única sentencia `SELECT`.
  `DESC` es una orden propia de la consola y no se ejecuta como SQL.
- `DESC` SQL sin argumentos devuelve exactamente `TABLA | TIPO | ES_ENTIDAD`; `DESC <tabla>`
  devuelve `CAMPO | TIPO SQL | RELACION`, marca PK/FK en el campo y permite seguir una FK hacia el
  detalle de la tabla destino. La metadata sale de JDBC, excluye catálogos del sistema y no falla la
  página cuando no hay datasource.
- El SELECT nativo se ejecuta en una transacción resource-local que siempre hace rollback. Se usa
  la metadata JDBC para headers y tipos, con degradación segura si el driver no la expone.
- En modo SQL se ocultan el menú de entidades y la generación de INSERT. El panel lista tablas y
  vistas, permite filtrar por texto/tipo y ejecuta el `DESC` emulado al hacer clic.
- Verificación vigente: `verify-demo.ps1` da **233 PASS / 0 FAIL**; con
  `-ContextPath /demo -MaxRows 3`, **241 PASS / 0 FAIL**.

## #12 — El menú por hover, y el bug del header

- **El menú ya no es por botón derecho**: sale solo al dejar el mouse **un segundo** sobre una
  entidad (`ESPERA_MENU = 1000`). El botón derecho se sacó por completo, así que volvió a ser el del
  navegador.
- **El clic sigue haciendo el `DESC`**, y el menú agrega las tres opciones con el nombre de la
  entidad adentro del rótulo (`DESC Libro`, `SELECT * FROM Libro`, `INSERT INTO Libro`), que es lo
  que hace que no haya que adivinar qué hace cada una.
- **Tres detalles del comportamiento que importan**: el menú se programa con `setTimeout` y se
  **cancela** si el mouse se va antes (`cancelarMenuProgramado`), se **sostiene** mientras el mouse
  está adentro del menú (`mouseleave` en el propio menú) para que llegues a elegir, y `cerrarMenu`
  cancela el temporizador — si no, un menú programado podía aparecer **después** de que el mouse ya
  se había ido.

### Los tres ajustes que salieron de usarlo

1. **Se sacó el `title` del ítem.** El tooltip nativo aparecía encima del menú y tapaba justo las
   opciones, además de repetir lo que el menú ya dice. El menú es la única ayuda ahora.
2. **El clic cierra el menú y no lo deja volver.** Antes, después de clickear una entidad el menú
   reaparecía solo y tapaba la grilla que se acababa de abrir. Se resolvió con `entidadSinMenu`: el
   clic marca esa entidad y `programarMenu` no la vuelve a mostrar hasta que el mouse **salga y
   entre** de nuevo (`cancelarMenuProgramado` limpia la marca al salir del ítem).
3. **El menú se cierra si alejás el mouse sin entrar.** Este era el peor y no era obvio: el menú
   aparece **al lado** del ítem, así que en cuanto sale el mouse ya no está ni sobre el ítem ni sobre
   el menú, y **ningún `mouseleave` se dispara**: el menú quedaba colgado para siempre. Se arregló con
   un `mousemove` a nivel documento (`cerrarSiSeAlejo`) que mira si el mouse sigue sobre el ítem dueño
   o adentro del menú, más un `mouseleave` del documento para cuando el mouse se va de la ventana.

   **Ojo con el orden en `menuDesc`**: ese ítem llama a `abrirEntidad`, que ya hace `cerrarMenu`, así
   que no hay que cerrarlo antes — si se cierra dos veces, la segunda llamada encuentra
   `entidadDelMenu` en null y se pierde la marca de `entidadSinMenu`, con lo que el menú volvía a
   aparecer. Es exactamente el bug que se estaba arreglando.

### Segunda ronda: el ancho del indicador y el trayecto al menú

4. **Los tres glyphs del indicador no medían lo mismo.** Reservar el espacio arregló el salto del
   `:hover`, pero al cambiar de `⇅` a `↑` o `↓` el header se seguía moviendo un poco: **`⇅` (U+21C5)
   no mide lo mismo que `↑` (U+2191) ni que `↓` (U+2193)** en una fuente monoespaciada. Se arregló
   con `display:inline-block; width:1em; text-align:center` en el `::after`: los tres estados miden
   exactamente igual y no depende de la fuente.
5. **El menú se cerraba al cruzar hacia él, sobre todo yendo lento.** El menú nacía **separado** del
   ítem (`caja.right + 4`), así que había un hueco donde el mouse no estaba ni en el ítem ni en el
   menú; y encima el divisor de paneles está justo en el medio, con lo que el hueco se agranda.
   Yendo rápido se llegaba antes de que el `mousemove` lo cerrara, yendo lento no. **Dos arreglos:**
   el menú ahora nace **pegado** al borde del ítem (`caja.right`, sin el `+4`), y el cierre mide la
   **distancia a los dos rectángulos** (ítem y menú) con una tolerancia de 24 px (`MARGEN_MENU`), en
   vez de exigir que el mouse esté dentro de uno de los dos. La tolerancia también cubre el caso de
   que el panel se desplace y el ítem se mueva mientras el mouse viaja.
6. **Volvió el botón derecho**, que ahora **abre el mismo menú de inmediato**, sin esperar el
   segundo. El hover sigue siendo el atajo y el clic sigue haciendo el `DESC`.
7. **El cursor del INSERT generado quedaba en el paréntesis equivocado.** Se usaba
   `sentencia.indexOf('(')`, o sea el **primer** paréntesis, que en un `INSERT` es el de la **lista de
   columnas** (los nombres de los campos) y no el de `VALUES` (los valores). Ahora la posición la
   calcula `posicionDeValores`, que busca `VALUES (` y cae al primer paréntesis sólo si no aparece.

8. **El INSERT generado tiraba el scroll del editor al final.** El texto quedaba bien, pero el
   usuario perdía de vista dónde había quedado el bloque. La causa: reemplazar `ta.value` y darle el
   foco hace que el navegador **scrollee para mostrar la selección**, y encima había un
   `setSelectionRange(seleccion.inicio, seleccion.fin)` que apuntaba al bloque recién insertado y
   llevaba la vista al **final** de ese rango.

   **Y ese `setSelectionRange` era código muerto**: la línea siguiente lo pisaba con
   `setSelectionRange(cursor, cursor)`, así que la sentencia "quedaba seleccionada" durante
   microsegundos y nunca se veía. El comentario decía que servía para mostrar qué se agregó, pero no
   cumplía ninguna función.

   **El arreglo:** se guardan `ta.scrollTop` y `ta.scrollLeft` antes de tocar nada y se restauran
   **después** de mover el cursor, que es el momento en que el navegador scrollea. Se eliminó el
   rango de selección que no se usaba (`insertarEnParrafo` ya no devuelve `seleccion`), con lo que
   además la función pura quedó más chica y sin estado de más.

9. **Se dejó de mover el cursor y ahora se selecciona el INSERT.** Con el cursor solo, era difícil
   darse cuenta de dónde había quedado el bloque (sobre todo si el párrafo estaba lejos de la vista).
   Ahora la sentencia insertada queda **seleccionada**, y eso se ve.

   **Se pierde el cursor listo para escribir en `VALUES (`**, y es una decisión consciente: se priorizó
   la visibilidad. Consecuencia a tener en cuenta: la próxima tecla que se toque **reemplaza** la
   selección, así que para completar la sentencia hay que hacer clic adentro.

   **Ojo con el scroll**: `setSelectionRange` hace que el navegador scrollee para mostrar la
   selección —fue exactamente la causa del salto al final que arreglamos en el punto 8—, así que la
   restauración de `scrollTop`/`scrollLeft` tiene que ir **después** de seleccionar. Las dos cosas van
   juntas: si en algún momento se saca una, la otra deja de tener sentido.

   El rango que devuelve `insertarEnParrafo` (`seleccion`) volvió a existir para esto: es el rango
   exacto de lo insertado, calculado con la misma cuenta que el texto, así que no puede quedar
   corrido.

   **Detalle a decidir**: la selección cubre **la sentencia entera**, comentario incluido (el
   `// Completa y ejecuta esta sentencia`), porque el comentario y el INSERT se insertan como una
   sola unidad. Si alguna vez molesta que una tecla se lleve también el comentario, hay que
   seleccionar sólo desde el `INSERT` (una línea de cambio).

10. **Foco y cursor al abrir.** Al cargar la página el foco arranca en el editor y el cursor en el
    carácter 0 (`ta.focus(); ta.setSelectionRange(0, 0)`), con `scrollTop`/`scrollLeft` en 0 para que
    se vea el comienzo de lo que quedó guardado y no el final. Uno abre la consola a escribir: no
    tiene que hacer clic primero.

11. **El INSERT va al principio cuando el cursor está arriba de todo.** Este tuvo **dos intentos
    fallidos** antes de quedar bien, y las dos trampas valen la pena:

    - **Primer intento**: `if (antes.trim().length === 0)`. No servía: cuando el cursor está arriba de
      todo y el texto arranca con líneas en blanco, `rangoParrafo` devuelve el párrafo de **abajo**
      (`inicio: 2, fin: 10` para `"\n\nSELECT 1"`), así que ese `antes` incluía **todo el texto** y la
      condición nunca se cumplía.
    - **Segundo intento**: `if (cursor <= parrafo.inicio && ...)`. Tampoco: con el cursor al principio
      del **primer párrafo** (en la línea del `SELECT`, no en una línea en blanco) también se cumple,
      y el INSERT terminaba **antes** de la consulta que estabas mirando.
    - **Lo que quedó**: la condición es que el cursor esté en una **línea en blanco** por encima de la
      primera línea escrita (`_lineaEnBlanco`). Es la distinción que hace el usuario: "estoy arriba de
      todo" no es lo mismo que "estoy en la primera línea".

    Los cinco escenarios quedaron cubiertos por tests: línea en blanco arriba (va al principio), primer
    párrafo (va después), final del primer párrafo (después), segundo párrafo (después del segundo) y
    editor vacío (al principio y sin saltos colgando).

### La trampa del template: `constant string too long`

Al agregar el último arreglo, el build falló con **`constant string too long`**: el límite de un
String en el class file es de **65535 bytes en UTF-8**, y la página (un solo text block) lo pasó. La
página mide **65522 bytes**: estábamos a **13 bytes** del límite.

Se partió el template en dos y aparecieron **dos trampas más**, que conviene tener anotadas:

1. **No alcanza con partir el texto en dos constantes y sumarlas en una tercera.** Si las dos mitades
   son `static final` con inicializador constante, el compilador **pliega la suma** en una única
   constante y el error vuelve igual. Las mitades se declaran `static String` (sin `final`), así no
   son constantes de compilación y cada una conserva su propio margen.
2. **Java resuelve las constantes en orden textual**, no en dos pasadas: `TEMPLATE` tiene que estar
   declarado **después** de las dos mitades.

Y un tercer detalle del corte: al partir el text block justo antes de `// ===== el detalle de una
entidad` se me coló un **`<script>` de más** en la segunda mitad (ya venía abierto desde la primera).
Lo cazó `node --check` sobre el JS extraído de la página servida, que es exactamente para lo que
está. **Al partir el template hay que verificar que la etiqueta `<script>` quede abierta una sola
vez**, y que el HTML cierre.

Con eso, `HqlConsolePage.TEMPLATE` es `TEMPLATE_PARTE_1 + TEMPLATE_PARTE_2` y no queda margen para
seguir agregando: **el próximo crecimiento de la página va a volver a chocar**. Cuando pase, hay que
partir en tres (o mover el CSS a un archivo aparte).

### #13 — El header se agrandaba al pasar el mouse

Era el `::after` del indicador de orden: la regla base era `content:''` y el glyph (`⇅`) aparecía
**recién en el `:hover`**. Ese carácter nuevo ocupa ancho, y como los `th` tienen `white-space:pre`,
al aparecer ensanchaba la columna y subía el alto.

**El arreglo:** el indicador existe siempre, con el mismo glyph y el mismo `margin-left`, y lo único
que cambia en el hover es la **opacidad**. Así el ancho se reserva desde el arranque y nada se mueve.

## #14 — Case de atributos: lo resuelve Hibernate (y es tolerante)

El pedido era que `SELECT l.ID FROM Libro l` fallara, como falla `INSERT INTO Libro li VALUES
li.TITULO='x'`. Se investigó y **la insensibilidad no es nuestra: es de Hibernate**, que resuelve los
nombres de atributo sin distinguir mayúsculas. Comprobado contra el demo: `l.ID`, `l.Id` y
`WHERE l.ID` funcionan los tres, y un atributo inventado (`l.noExiste`) sí falla con
`UnknownPathException`.

O sea que hay una **inconsistencia real**: los `SELECT` (que van tal cual a Hibernate) toleran
cualquier case, y el `INSERT`/`UPDATE` de la consola exigen el exacto porque el parser resuelve los
nombres contra el metamodelo (`AttributeBinder._attributeOf`).

**Se decidió no arreglarlo** (a pedido del dueño del repo): hacerlo estricto requiere escanear el HQL
y resolver cada `alias.atributo` contra el metamodelo —distinguiendo alias, atributos, funciones,
constructores `new X(...)`, literales y subconsultas—, que es un análisis sintáctico nuevo y el más
riesgoso de la lista. Queda **documentado en el `README`** como comportamiento conocido.

## Pendientes, acordados y NO hechos todavía

Se dejaron para más adelante por ser los que más riesgo tienen (los tres tocan parseo o arquitectura de
la página, no cosmética):

| Feature | Por qué quedó pendiente |
|---|---|
| **Solapas con X** para cada `SELECT`/`DESC` | Es el más caro: reescribe la arquitectura del panel derecho (cada solapa con su caja/tabla/estado) y hay que decidir antes qué pasa con los `DESC` sin argumentos, con el panel de detalle de abajo y con dónde caen los INSERT y los errores. |
| **Color de los comentarios** | No se puede con un `<textarea>`: hace falta el truco del overlay (`<pre>` pintado detrás + textarea transparente encima), y eso se lleva mal con el scroll horizontal. El parseo de comentarios ya está hecho (#11), así que falta sólo lo visual. |

Verificación: `verify-demo.ps1` → **196 PASS / 0 FAIL**; con `-ContextPath /demo -MaxRows 3` →
**203 PASS / 0 FAIL** (venía de 144/148). Las features están cubiertas end-to-end; el clic en sí (el
header, el del panel lateral, el menú contextual) no lo ve ningún test automático, eso se mira en el
navegador. Lo que sí se prueba de verdad, en Node sobre la página que sirve el jar, son las funciones
puras: el comparador del orden, el armado del INSERT y del SELECT del menú.

## #7 a #10 — Los cuatro features chicos

- **#7 scroll horizontal**: es `wrap="off"` en el `<textarea>` más `wrap:off; white-space:pre;
  overflow-x:auto` en el CSS. El atributo es el que manda en algunos navegadores, así que van los dos.
- **#8 párrafo pintado**: `rangoAEjecutar` ahora devuelve también `inicio`, `fin` y un flag `pintar`.
  `pintarRango` hace `focus()` + `setSelectionRange`, y sólo cuando la sentencia salió del párrafo:
  si el usuario ya tenía algo seleccionado, su selección no se toca (`pintar: false`). El `focus()` es
  imprescindible: sin el foco, el navegador no pinta la selección.
- **#9 marca de `@Id`**: el `*` va **pegado al nombre del atributo** (`id*`) y no en una columna
  nueva, para no romper el contrato de que los títulos de `from <Entidad>` son exactamente los de la
  columna `ATRIBUTO`. Consecuencia: los títulos de la grilla aplanada siguen saliendo pelados (el
  runner usa `attribute.getName()`, no el texto del `DESC`), y en los tests el `$atributosDesc` se
  calcula sacándole el `*` con `-replace '\*$',''`.
- **#10 menú contextual**: `contextmenu` sobre los botones del panel lateral, con `preventDefault`.
  El menú vive en el `body`, se posiciona con coordenadas de pantalla y se corre hacia adentro si no
  entra. Se cierra con clic afuera, `Escape`, scroll (con `capture:true`, para agarrar también el
  scroll de la lista) y `resize`.
  - **[Generar INSERT]** pide el `DESC` de la entidad y arma la sentencia **en el cliente**
    (`insertDeEntidad`): excluye el `id` (lo detecta por el `*`), y elige el valor de ejemplo por
    `TIPO JAVA` (`valorDeEjemplo`). **No la ejecuta**: la escribe en el editor y te deja el cursor al
    final. Es lo que se acordó.
  - **[SELECT *]** corre `SELECT * FROM Entidad LIMIT 100` sin pasar por el editor. El 100 es una
    constante (`LIMITE_MENU`) y el tope global `max-rows` sigue ganando si es menor, porque el `LIMIT`
    del motor ya funciona así.
  - **Ojo con la ubicación de `LIMITE_MENU`**: va **dentro** del bloque `// INICIO funciones puras`,
    porque `selectDeEntidad` la usa y `verify-demo.ps1` extrae sólo ese bloque para correrlo en Node.
    Si se mueve afuera, el test falla con `ReferenceError: LIMITE_MENU is not defined`.

## #11 — Comentarios `//`, `#` y `--` (sin color)

**El pedido era cosmético, pero el trabajo real fue de parseo.** Se implementó lo funcional: los
comentarios no rompen nada y se excluyen antes de ejecutar. El color quedó pendiente (ver arriba).

- **Dónde se excluyen**: en `HqlConsoleController.execute` (antes de partir por `;` y de mirar
  `allow-writes`) **y también en `HqlQueryRunner.execute`**, porque el runner es API pública y tiene
  que funcionar igual si alguien lo usa embebido. En el runner la exclusión va **antes** de
  `firstWord`: si no, un `// nota` arriba haría que la sentencia no se reconociera como INSERT/SELECT.
- **`Text.withoutComments` reemplaza cada comentario por un ESPACIO**, no lo borra: si se borrara,
  `titulo-- comentario\n, precio` quedaría `titulo, precio`... y en otros casos dos tokens separados
  por un comentario terminarían pegados (`campo` + `--x` + `valor` → `campovalor`), cambiando el
  significado de la sentencia.
- **Los cinco escáneres de `Text` ahora saltean comentarios**: `indexOfKeyword` (un `where` comentado
  no es el `WHERE`), `indexOfTopLevel`, `matchParenthesis`, `unclosedParenthesis`, `splitTopLevel` y
  `splitStatements`. El caso que más dolía era `splitStatements`: un `;` adentro de un comentario
  **partía la sentencia al medio**.
- **Un comentario adentro de un literal es texto**: `'a--b'` se respeta tal cual, porque el escaneo
  primero mira si está dentro de comillas.
- **Un comentario cuenta como parte del párrafo.** Se evaluó hacerlo frontera de párrafo y se
  descartó: si el comentario separara, un `// Completa y ejecuta` arriba del INSERT generado haría que
  el `Ctrl+Enter` ejecutara **el comentario** en vez de la sentencia. Como parte del párrafo, el
  comentario se descarta al ejecutar y la sentencia corre igual.
- **Ojo con el Javadoc de `Text`**: la primera versión documentaba los comentarios de bloque como
  `{@code /* */}` adentro de un Javadoc, y eso **cierra el comentario** y no compila. Se escribe con
  palabras.

### Bug: el INSERT generado borraba todo el editor

`[Generar INSERT]` hacía `ta.value = sentencia`, o sea que pisaba lo que estuvieras escribiendo. Ahora
usa `insertarEnParrafo`, que inserta **abajo del párrafo del cursor** dejando una línea en blanco de
cada lado, y deja el cursor adentro del paréntesis de las columnas.

**El detalle que costó dos vueltas:** los separadores no se pueden agregar a ciegas. Entre dos
párrafos el hueco **ya aporta sus saltos**, así que sumarle `\n\n` dejaba cuatro líneas en blanco en
vez de una. Y contra los bordes del texto (editor vacío, o cursor en el último párrafo) no hay que
agregar nada. La cuenta la hace `_saltosQueFaltan`, que mira los saltos que ya hay y agrega sólo los
que faltan para llegar a una línea en blanco. Los tests cubren los tres casos: hueco existente,
borde de abajo y editor vacío.

## #6 — Ordenar la grilla clickeando el header

**Decisión: todo en el cliente, y sólo sobre las filas que se ven.** No se vuelve a consultar la
base: un click ordena el array que ya está en memoria. El orden que pidió la sentencia es el punto de
partida y no se toca.

- **Hizo falta un dato nuevo en el JSON: `types`.** El pedido original era "si tenemos los tipos Java
  podemos deducir...", pero el JSON sólo traía `headers` (nombres) y `rows`, así que el cliente no
  tenía de dónde sacar el tipo. Se agregó `types` a `HqlResult`, con un tipo por columna:
  `TEXTO | NUMERO | FECHA | BOOLEANO | OTRO` (enum `HqlResult.ColumnType`).
- **De dónde sale cada tipo, y por qué no siempre igual:**
  - Consulta con `SELECT` explícito: se mira **el valor de las celdas**. Se recorren todas las de la
    columna (no sólo la primera: una columna puede empezar en NULL y tener números abajo). Los
    temporales llegan ya saneados a texto ISO, así que se reconocen por la forma (`_looksLikeDate`).
  - `from <Entidad>` (aplanado): se mira el **metamodelo**, porque una relación se aplana al id de la
    FK y si la fila no tiene autor la celda es NULL y no dice nada. El tipo Java del atributo sí lo
    dice siempre. Es el único lugar donde el runner necesita el metamodelo, que se guarda en un campo
    `_metamodel` (con el comentario del por qué y de qué pasa si dos requests se pisan).
  - `DESC`: tipos fijos (la lista de entidades tipa `CAMPOS` como `NUMERO`; el detalle, todo `TEXTO`).
- **Con 0 filas el tipo queda `OTRO`**, que se ordena como texto. No hay nada que mirar; es el
  comportamiento honesto.
- **Los NULL van siempre al final, en las dos direcciones.** Eso obliga a que `compararCeldas` **no**
  aplique la dirección: el factor vive en `ordenarFilas`, que distingue "sin valor" (siempre al final)
  de "con valor" (se da vuelta). Si se invirtiera todo, ordenar descendente arrancaría con una
  pantalla de NULL.
- **TEXTO es alfabético puro**, sin `numeric:true`: si la columna es texto, "10" va antes que "9", que
  es lo que significa ordenar texto. Si querés orden numérico, la columna tiene que ser `NUMERO`
  (o sea, el campo numérico en la entidad).
- **Las filas originales se guardan** en `tabla.__filas` (colgado de la tabla, no en una variable
  global: la grilla de resultados y la del detalle ordenan por su cuenta). Cada click reordena desde
  ahí, así que cambiar de columna no acumula recortes.
- **Ojo con los listeners:** ordenar rehace el `tbody` entero y las filas viejas desaparecen con sus
  listeners. Por eso `ordenarPor` llama a `recablearFilas(tabla)`, que vuelve a enganchar el click de
  las filas del `DESC` (si no, después de ordenar la lista de entidades, las filas nuevas dejarían de
  abrir el detalle).
- La flechita del header va como `content` del `::after` para no ensuciar el `textContent` del `th`,
  que es el nombre de la columna (y que `hacerListaClickeable` busca por texto).

## #1 — Orden de `DESC`

`DESC <Entidad>` pasó de `CAMPO | TIPO SQL | ATRIBUTO | TIPO JAVA` a
**`ATRIBUTO | TIPO JAVA | CAMPO | TIPO SQL`**: primero lo que uno escribe en una sentencia, después
lo que existe en la base. Sólo cambia el orden de las filas que arma `EntityDescriber.describe(...)`
(ahora `List.of(attribute.getName(), Mapping.javaTypeName(attribute), campo, sqlType)`). Los nombres
de las columnas no cambiaron, así que el resto de la página (que busca `TIPO JAVA` por nombre) sigue
igual. `DESC` sin argumentos no se tocó.

**Cuidado con los tests:** los índices de las columnas de `DESC` estaban hardcodeados en
`verify-demo.ps1` (`$_[2]`, `$fk[3]`, etc.) y hubo que reindexarlos.

## #2 — `INSERT` posicional con relaciones

**Ya funcionaba y no se tocó el motor.** La duda tenía sentido porque el formato (3) no tenía test
con relaciones, pero el camino ya estaba: `AttributeBinder.resolve(...)` detecta que el destino es una
relación y devuelve el id como tipo destino, y `value(...)` lo convierte y arma el
`em.getReference(...)`. O sea que `INSERT INTO Libro (titulo, autor) VALUES ('...', 1)` asigna la FK
al autor 1 igual que `li.autor=1`. Lo único que se agregó son los chequeos en `verify-demo.ps1`
(`Libro (titulo, autor)` y `Empleado (nombre, salario, departamento)`).

## #3 — `SELECT * FROM Entidad`

HQL no acepta el `*` (Hibernate tira `SyntaxException`), así que se **traduce**: `HqlQueryRunner` le
saca el `select *` y deja `from Entidad ...`, que ya entra por el camino aplanado. Es a propósito que
sea una traducción y no un caso especial del aplanado: así el `WHERE`, el `ORDER BY`, el `LIMIT` y los
alias funcionan sin escribir una línea más.

- Sólo se toca la forma exacta `select * from ...`. Un `SELECT` explícito que no sea `*` **no** se
  aplana: se sigue devolviendo `Tipo#id`, como antes.
- Sin el `FROM` detrás, la sentencia se deja como está para que el error lo dé Hibernate.
- El método es `_sinSelectEstrella(String)` y el reconocimiento es puramente textual (`Text`), no
  toca paréntesis ni literales.

## #4 — `LIMIT n` al final

`LIMIT n` va **al final y nada más que al final**: la sentencia puede ser larga, con `WHERE` y
`ORDER BY`, y terminar en `LIMIT n`. Se aplica con **`setMaxResults`** (el *maxRows* de JDBC), que es
lo que corta la cantidad de filas devueltas. `fetchSize` **no** sirve para esto: sólo insinúa de a
cuántas filas traer por viaje.

- Sólo aplica a las **consultas** (`select` / `from`), que es lo que se acordó. Un `DELETE` o un
  `UPDATE` con `LIMIT` no se toca: sigue siendo un error de Hibernate.
- El escaneo es de nivel 0: un `limit` dentro de un literal (`LIKE '%limit 5%'`) o de una subconsulta
  no se confunde con la cláusula.
- Interacción con el tope global `max-rows`: **gana el menor**. Si `max-rows` es menor que el `LIMIT`,
  el resultado se marca como truncado y el mensaje lo dice (`LIMIT 10 recortado antes por el tope de
  3 filas`). Si el `LIMIT` entra en el tope, **no** es una truncación y no se avisa: es lo que el
  usuario pidió. Por eso, cuando el `LIMIT` manda, se piden las filas justas y no una de más.
- Errores con mensaje propio (400): `LIMIT` sin número, con texto, `0`, o más de uno en la sentencia.

## #5 — Panel lateral de entidades

Tercer panel, a la **izquierda del editor**, angosto (150 px) y sólo con los nombres:

- Se llena con el mismo `DESC` sin argumentos, pedido **una sola vez** al abrir la página
  (`asegurarEntidades()`). Si el pedido falla, el panel queda vacío y no pasa nada más.
- Clickear un nombre **equivale a ejecutar `DESC <Entidad>`**: reemplaza la grilla principal y marca
  esa entidad como elegida. **No pisa el editor**: el panel es un atajo para mirar, no para borrar lo
  que estabas escribiendo. Por eso el cuerpo de `ejecutar()` se separó en `ejecutarTexto(hql, etiqueta)`,
  que es lo que también usa el panel.
- Se contrae y se expande con el botón de su cabecera; contraído queda una franja de 30 px con el
  botón (el control para volver a abrirlo). El estado se persiste en `localStorage`
  (`hql-console.entidades-abierto`), como el ancho del editor.
- Por debajo de 720 px de ancho se esconde entero, como los divisores.
- La entidad elegida también se marca cuando el `DESC` se ejecuta a mano desde el editor, y se
  desmarca si la sentencia que corre no es un `DESC` de una entidad.
- **Efecto colateral que hubo que arreglar**: el arrastre del divisor medía el ancho del editor desde
  el borde del split, así que con el panel adelante el divisor quedaba corrido todo el ancho del
  panel. Ahora mide desde el borde del propio panel del editor (`anchoSegunPuntero` usa
  `panelEditor.getBoundingClientRect()`), pero el porcentaje sigue siendo sobre el split entero,
  porque el `--ancho-editor` es un `%` del split.

## Trampas del código que hay que respetar

- **El JS de la página vive en un text block de Java.** Una barra invertida va **doble** (`\\n`,
  `\\s`, `\\S`), o Java la convierte en otra cosa y **rompe el JavaScript sin que falle la
  compilación**. Pasó varias veces durante el desarrollo. `verify-demo.ps1` lo caza con
  `node --check` sobre el script que sirve el jar.
- **Ojo con `\uXXXX` en el text block**: Java procesa los escapes Unicode *antes* de lexear, así que
  `'\u00ab'` termina siendo el carácter literal en el JS. Funciona, pero conviene saberlo.
- **Las funciones puras** (sin DOM) van entre los marcadores `// INICIO funciones puras` y
  `// FIN funciones puras`: `verify-demo.ps1` extrae ese bloque del HTML **que sirve el jar** y lo
  corre en Node. Si agregás una función pura, ponela ahí y agregale casos.
- **En `verify-demo.ps1` no uses tildes en los patrones ni en los literales escritos**: PowerShell
  5.1 lee el archivo como ANSI si no tiene BOM, así que se comparan mal. Para texto con tilde en los
  tests de Node, usá escapes (`'Se insert\u00f3 1 fila'`).
- **Los comentarios y la documentación van en español**, igual que el resto del proyecto.
- **`verify-demo.ps1` no corre en el sandbox confinado**: Gradle forkea su daemon capturando la
  salida por pipe, y eso el sandbox lo deniega. Hay que correrlo con acceso completo.

## Lo que se decidió NO hacer

- Multi-fila clásico (`VALUES (...), (...)`): lo resuelve Hibernate como bulk, sin `@PrePersist` ni
  `NOW`. Para datos de prueba está el lote con `;`.
- Mostrar los ids de las filas afectadas en el dry-run.
- Confirmación para `INSERT` (sólo avisa).
- `LIMIT` en `UPDATE`/`DELETE`.
- Tocar `DESC` sin argumentos.
- Que el panel lateral pise el editor con el `DESC` clickeado.
