# Plan de trabajo — Feature #15: editor SQL (sólo lectura)

Este documento es el plan de implementación de la **solapa SQL** de la consola HQL. Se apoya en
las decisiones ya tomadas y documentadas en `PENDIENTES.md` (features #1 a #14) y en el análisis
previo: la solapa SQL permite ejecutar **consultas** contra la base en SQL nativo, restringido a
**sólo `SELECT`** (sin DDL ni DML), para poder tocar tablas y vistas que **no** son entidades y usar
el SQL específico del motor.

La regla que manda todo el plan: **nada de lo que hoy funciona puede dejar de funcionar**, y todo lo
nuevo tiene que ser **verificable de forma automática** (unidad en Node + end-to-end en
`verify-demo.ps1`), salvo lo puramente visual (layout, solapas), que se verifica por estructura del
HTML servido y se mira a mano.

> Convenciones del repo que aplican a este plan: comentarios y documentación **en español**, el JS de
> la página vive en un **text block de Java** (barras invertidas dobles), las funciones puras van
> **entre `// INICIO funciones puras` y `// FIN funciones puras`** (las extrae `verify-demo.ps1` para
> correrlas en Node), y `verify-demo.ps1` no usa tildes en patrones ni literales (PowerShell 5.1 lee
> el archivo como ANSI).

---

## 1. Objetivo

Agregar, en la sección del editor, **dos solapas**: `HQL` (la actual) y `SQL` (nueva). Cada solapa
tiene su propio `<textarea>`, su propia persistencia y su propio comportamiento. La solapa SQL:

- ejecuta **sólo `SELECT`** contra la base, en SQL nativo;
- muestra a la izquierda la lista de **tablas y vistas** (nombres físicos), con **filtro**;
- `DESC` en modo SQL lista **campos y tipos SQL** (no atributos), sin navegación;
- no tiene menú contextual ni generación de `INSERT`;
- respeta el tope global `hql-console.max-rows`.

---

## 2. Alcance

### 2.1. Entra

| # | Punto | Resumen |
|---|---|---|
| A | Solapa SQL | Segundo `<textarea id="sql">`, header de solapas, persistencia propia y recuerdo de la solapa activa. |
| B | Ejecución `SELECT` | `language='sql'` en el `POST`; el backend valida y ejecuta SQL nativo de lectura. |
| C | Bloqueo de DDL/DML | Whitelist: la sentencia tiene que empezar con `select`. Todo lo demás se rechaza con 400. |
| D | Panel de tablas | En modo SQL, el panel izquierdo lista tablas y vistas del **esquema de la conexión**. |
| E | Filtro | Input de búsqueda por texto + combo de tipo (`Todas` \| `Tablas` \| `Vistas`). |
| F | `DESC` de campos | En modo SQL, `DESC <tabla>` devuelve `CAMPO \| TIPO SQL \| NULO \| PK \| FK`, aclarando PK y FK donde corresponda, sin `ATRIBUTO` ni navegación. |
| G | Menús deshabilitados | En modo SQL no hay menú de hover ni de botón derecho; el click de una tabla ejecuta `SELECT *`. |
| H | Comentarios | Se sigue parseando (`//`, `#`, `--`) igual que en HQL. |
| I | Persistencia | Dos claves independientes en `localStorage`: la de HQL (existente) y la de SQL (nueva). |

### 2.2. No entra (a propósito)

- SQL de escritura (`INSERT`, `UPDATE`, `DELETE`) y DDL (`CREATE`, `DROP`, `ALTER`, `TRUNCATE`,
  `MERGE`, `GRANT`, ...). Queda **sólo lectura**.
- Navegación de relaciones en modo SQL (no aplica: son tablas, no entidades).
- Generación de `INSERT` en modo SQL.
- `dry-run` en modo SQL (no hay escrituras que confirmar).
- Aplanado automático y traducción de `SELECT *` (eso es gramática de la consola HQL; en SQL el `*`
  lo entiende la base).

---

## 3. Decisiones de diseño

### 3.1. Un solo endpoint, dos lenguajes

Se reutiliza `POST {path}/api/execute`. El body pasa a `{hql, dryRun, language}`:

- `language` ausente o `"hql"` → comportamiento **actual** (cero cambios funcionales).
- `language == "sql"` → rama nueva de sólo lectura.

Así no se duplica el transporte, el manejo de errores ni el `base` de la página, y el modo HQL queda
intacto por construcción (mismo camino de código que hoy).

### 3.2. Validación por lista blanca, no por lista negra

El `_isWrite` actual mira `insert/update/delete` y se saltearía `truncate`/`drop`/etc. En modo SQL se
invierte: **lo único permitido es que la primera palabra sea `select`**. Se evalúa **después** de
`Text.withoutComments` y de `Text.splitStatements` (igual que hoy), y **antes** de tocar la base.

- Una sola sentencia por ejecución en modo SQL (no hay lote de SQL). Si `splitStatements` trae más de
  una, se rechaza con mensaje claro.
- `DESC <tabla>` en modo SQL **no** va por acá: es una sentencia propia de la consola (ver 3.5).
- **Extensión futura documentada, no implementada:** aceptar `with ... select`. Si el día de mañana
  se agrega, hay que validar a nivel 0 que no aparezca `insert`/`update`/`delete`/`merge` (CTE
  modificante). Por hoy, `with` no se permite y el mensaje lo dice.

### 3.3. Motor SQL nativo de lectura

Nuevo método en `HqlQueryRunner`: `executeSqlReadOnly(String sql)`.

- `EntityManager` nuevo por sentencia, transacción resource-local, `rollback` al final (lectura), tal
  como `_runBulkWrite`/`_runQuery`.
- `em.createNativeQuery(sql)` + `setMaxResults(tope)` con la misma cuenta de tope que hoy
  (`maxRows`, y "pedir una de más" para detectar truncación). El `LIMIT` del usuario, si lo hay, lo
  entiende el motor: **no** se parsea (en SQL no es gramática de la consola).
- **Headers y tipos**: desde la metadata de columnas. Dos caminos, en este orden:
  1. `ResultSetMetaData` (vía JDBC), que da el nombre y el tipo SQL real → mejor tipado que el
     heurístico `_columnTypes` de hoy.
  2. Fallback a `NativeQuery.getReturnTypes()` (Hibernate), igual que el código ya acepta
     `Tuple.class` como camino no portable con degradación.
- **Filas**: se reutiliza la maquinaria existente (`_cells`/`_toRows`) para sanear valores; nunca
  entidades crudas.
- El resultado se devuelve con `HqlResult.query(headers, types, rows, truncated, elapsedMs, message)`,
  que **no se modifica**.

### 3.4. Listado de tablas del esquema

En modo SQL el panel izquierdo lista **tablas y vistas del esquema de la conexión**:

- Se usa `DatabaseMetaData.getTables(...)` con el `schema` de la conexión (el mismo que ya lee
  `EntityDescriber`), y `types = {"TABLE","VIEW"}` (+ `"MATERIALIZED VIEW"` si el motor lo reporta).
- **Se excluyen los catálogos del sistema** (`information_schema`, `pg_catalog`, `sys`, etc.) y las
  tablas temporales.
- Se marca cuáles corresponden a una **entidad mapeada** (para distinguirlas), pero **no se excluye
  el resto**: el valor del modo SQL es justamente ver lo no mapeado.
- Si no hay `DataSource` o falla la metadata, el panel queda vacío y **no es un error para el
  usuario** (mismo criterio que `asegurarEntidades`).

### 3.5. `DESC` de campos en modo SQL

`DESC <tabla>` en modo SQL devuelve una grilla distinta a la de HQL:

- Headers: `CAMPO,TIPO SQL,NULO,PK,FK`.
- **`NULO`**: `SI` / `NO`, desde `DatabaseMetaData.getColumns` (columna `NULLABLE`).
- **`PK`**: `PK` en la columna (o columnas) que forman la clave primaria, `-` en el resto. Sale de
  `DatabaseMetaData.getPrimaryKeys`. Si la PK es compuesta, **todas** sus columnas llevan `PK`.
- **`FK`**: el destino de la clave foránea con el formato `TABLA(CAMPO)` (por ejemplo
  `AUTORES(ID)`), o `-` si la columna no es FK. Sale de `DatabaseMetaData.getImportedKeys`
  (`PKTABLE_NAME` + `PKCOLUMN_NAME`); si una columna participa de más de una FK, se listan
  separadas por coma.
- **Sin** `ATRIBUTO`, `TIPO JAVA` ni `RELACION`; **sin** filas clickeables (no hay navegación).
- El `DESC` actual de entidades (modo HQL) **no se toca**: es otra rama.

### 3.6. Comentarios

Se reutiliza `Text.withoutComments`, que ya soporta `//`, `#` y `--`. En modo SQL se aceptan los
tres (aunque no todos los motores soporten los tres), porque el objetivo es la comodidad del usuario
y el comentario se descarta antes de mandar la sentencia al motor. Mismo comportamiento que HQL.

### 3.7. Persistencia y solapa activa

- `CLAVE_TEXTO` actual (`hql-console.consulta`) → queda para HQL.
- Nueva `CLAVE_TEXTO_SQL = 'hql-console.consulta-sql'` → para SQL.
- Nueva `CLAVE_SOLAPA = 'hql-console.solapa'` → recuerda cuál estaba activa.
- Se reutiliza el envoltorio `ALMACEN` (con su fallback a memoria si `localStorage` no está).

### 3.8. Modo SQL como estado del documento

Para habilitar/inhabilitar cosas sin duplicar listeners, el `body` lleva una clase `modo-sql`
(o `modo-hql`). El CSS y las funciones que dependen del modo miran esa clase. Así los menús y el
`DESC` de entidades se ocultan con CSS y se omiten en el JS sin ramificar la lógica existente.

---

## 4. Fases de trabajo

Cada fase es incremental y verificable. El orden no es casual: la Fase 0 despeja el obstáculo del
tamaño del template antes de agregar código.

### Fase 0 — Preparar el template (prerrequisito técnico)

**Objetivo:** garantizar margen para el HTML/CSS/JS nuevo.

**Contexto:** `HqlConsolePage.TEMPLATE` es `TEMPLATE_PARTE_1 + TEMPLATE_PARTE_2` (ambas
`static String` **sin `final`**, y `TEMPLATE` declarado **después**). El límite de 65535 bytes es
**por literal del class file**. La página ya estaba a ~13 bytes del techo antes de partirla; hoy el
techo aplica **a cada fragmento**.

**Tareas:**
1. Medir en bytes `TEMPLATE_PARTE_1` y `TEMPLATE_PARTE_2` (script de un solo uso o `Get-Item` sobre
   el fuente), para conocer el margen real.
2. Si el código nuevo no entra con comodidad: **externalizar el CSS** a un recurso estático servido
   por el controller (mismo jar, sin CDN, sin internet) — es la solución más sana y libera margen
   para varios features. Alternativa rápida: **partir en tres** (`TEMPLATE_PARTE_3`).
3. Si se parte: respetar las tres trampas ya documentadas (no `final` en los fragmentos; `TEMPLATE`
   al final; verificar que `<script>` quede abierto **una sola vez**).

**Verificación:** el build compila; `verify-demo.ps1` sigue en verde; `node --check` del JS servido
pasa.

### Fase 1 — Backend: idioma y validación de lectura

**Objetivo:** el endpoint entiende `language='sql'` y rechaza todo lo que no sea lectura.

**Archivos:** `HqlConsoleController.java` (y, si hace falta, `HqlConsoleProperties`).

**Tareas:**
1. Leer `language` del body (default `"hql"`).
2. En `language == "sql"`:
   - sacar comentarios (`Text.withoutComments`); si queda vacío, 400 con el mensaje actual;
   - partir por `;`; si hay más de una sentencia, 400 ("en modo SQL se ejecuta una sentencia por vez");
   - validar que la primera palabra sea `select` (lista blanca). Si no, **400** con un mensaje que
     enumere lo permitido ("En la solapa SQL sólo se permite SELECT. ...").
   - **No** mirar `allow-writes`: el modo SQL es lectura por definición.
   - **No** aceptar `dryRun` (se ignora).
3. Delegar en `runner.executeSqlReadOnly(statement)`.

**Verificación (end-to-end, ver §5):** `SELECT` ok; `INSERT/UPDATE/DELETE/DROP/TRUNCATE/ALTER/CREATE/
MERGE` → 400; comentarios ok.

### Fase 2 — Backend: motor SQL nativo de lectura

**Objetivo:** ejecutar el `SELECT` y devolver `HqlResult.query(...)`.

**Archivos:** `HqlQueryRunner.java`.

**Tareas:**
1. `public HqlResult executeSqlReadOnly(String sql)`.
2. `EntityManager` nuevo, transacción resource-local, `setMaxResults` con el tope (y "una de más"
   para detectar truncación, igual que `_runQuery`).
3. `createNativeQuery(sql)`; headers y tipos desde `ResultSetMetaData` con fallback a
   `getReturnTypes()`.
4. Mapear filas con la maquinaria existente; `rollback`; cerrar el `EntityManager`.
5. Message de truncación coherente con el de HQL ("truncado a N filas").

**Verificación (end-to-end):** `SELECT` crudo devuelve filas/headers/tipos; el tope trunca y avisa;
un `SELECT` inválido devuelve 400 con `cause`.

### Fase 3 — Backend: listado de tablas y `DESC` de campos (modo SQL)

**Objetivo:** proveer la metadata que consume el panel y el `DESC` en modo SQL.

**Archivos:** `EntityDescriber.java` (o clase nueva `TableDescriber`), endpoint del controller.

**Tareas:**
1. `tablasDelEsquema()`: `getTables` filtrado por esquema, sin catálogos del sistema, con marca de
   "es entidad mapeada".
2. **Decisión a tomar** (una sola): `DESC` sin argumentos en modo SQL devuelve la lista de tablas.
   **Recomendado:** reutilizar la forma de `describeEntities` pero con header `TABLA,TIPO,ES_ENTIDAD`
   (o similar), para no inventar un endpoint nuevo.
3. `descDeTabla(String tabla)`: `CAMPO,TIPO SQL,NULO,PK,FK` con `getColumns` (nombre, tipo y
   nullable) + `getPrimaryKeys` (PK, compuesta incluida) + `getImportedKeys` (FK y su destino
   `TABLA(CAMPO)`).

**Verificación (end-to-end):** la lista incluye una tabla conocida (ej. `LIBROS`); no incluye
`INFORMATION_SCHEMA`; `DESC LIBROS` (modo SQL) trae `CAMPO`/`TIPO SQL` y no `ATRIBUTO`.

### Fase 4 — Frontend: solapas y persistencia independiente

**Objetivo:** dos editores, cada uno con su texto.

**Archivos:** `HqlConsolePage.java`.

**Tareas:**
1. Header de solapas (`role="tablist"`, dos `button` con `role="tab"`), **fuera** del `<div
   class="barra">` para no romper el check existente "el encabezado solo dice HQL Console".
2. Segundo `<textarea id="sql">` con `spellcheck="false" wrap="off"`, mismo CSS que `#hql`.
3. Una función `editorActivo()` que devuelve el textarea de la solapa activa; adaptar
   `rangoAEjecutar`, `pintarRango`, `refrescarSeleccion` e `insertarEnEditor` para usarla.
4. Persistencia: `CLAVE_TEXTO` (HQL) y `CLAVE_TEXTO_SQL` (SQL), cada una con su restitución y su
   guardado (input, `pagehide`, `visibilitychange`). Recordar solapa activa en `CLAVE_SOLAPA`.
5. Toggle `body.modo-sql` / `body.modo-hql`.

**Verificación (estructura + Node):** la página trae los dos `textarea` y las solapas; `node --check`
pasa; las claves nuevas aparecen en el HTML.

### Fase 5 — Frontend: panel de tablas con filtro

**Objetivo:** el panel izquierdo, en modo SQL, lista tablas/vistas con filtro.

**Archivos:** `HqlConsolePage.java` (HTML/CSS/JS) y funciones puras nuevas.

**Tareas:**
1. En la cabecera del panel, en modo SQL: input de búsqueda + `<select>` de tipo
   (`Todas`/`Tablas`/`Vistas`).
2. Funciones puras (dentro del bloque de funciones puras, para test en Node):
   - `normalizarFiltro(texto)` → `trim().toLowerCase()`.
   - `pasaFiltroTabla(tabla, filtro, tipo)` → decide si una tabla se muestra.
   - `filtrarTablas(tablas, filtro, tipo)` → devuelve la lista filtrada (no muta la original).
   - `claveDeTexto(language)` → la clave de `localStorage` según el idioma.
   - `esSoloLectura(primeraPalabra)` → whitelist (espejo de la del backend, para feedback inmediato).
3. Click en una tabla: `SELECT * FROM <tabla> LIMIT 100` (constante `LIMITE_MENU` reutilizada) por el
   camino de ejecución.
4. CSS: en modo SQL el panel puede pasar a ~220 px **con una regla aparte**
   (`body.modo-sql #panel-entidades { width:220px; }`), **sin** tocar la regla base
   `#panel-entidades { flex:0 0 auto; width:150px ... }` (hay un test que la busca literal).

**Verificación (Node + e2e):** casos de filtro por texto, por tipo, sin coincidencias, con
mayúsculas/acentos; la lista del backend se filtra sin volver a pedir nada.

### Fase 6 — Frontend: ejecución, resultados y modo SQL

**Objetivo:** ejecutar en modo SQL y apagar lo que no aplica.

**Archivos:** `HqlConsolePage.java`.

**Tareas:**
1. `pedir()` agrega `language` al body.
2. `ejecutar()` toma el texto del editor activo y el `language` de la solapa.
3. En modo SQL:
   - no hay confirmación (`pideConfirmacion` no aplica: sólo lectura);
   - no hay `dryRun`;
   - el panel de resultados, el ordenado de headers, el JSON crudo y los errores se reutilizan
     **tal cual**;
   - menú de hover y de botón derecho: **ocultos** (CSS por `body.modo-sql`);
   - "Generar INSERT" y `DESC` de entidades: no disponibles;
   - el click de tabla ejecuta su `SELECT *`.
4. `DESC` en modo SQL: si el texto empieza con `desc`, manda `{language:'sql'}` y el backend
   devuelve la grilla de campos.

**Verificación (e2e + estructura):** un `SELECT` en modo SQL llena la grilla; apretar Ctrl+Enter corre
lo mismo; los menús no aparecen (estructura/CSS); un `DROP` da 400 y muestra el error en la caja.

### Fase 7 — Tests (nuevos) y regresión

Ver §5 completo. Es una fase propia porque **no se considera terminado sin los tests**.

### Fase 8 — Documentación

**Tareas:**
1. `README.md`: sección de la solapa SQL (qué hace, qué no, cómo se limita a `SELECT`, `max-rows`).
2. `PENDIENTES.md`: alta del feature **#15** con las decisiones y las trampas que aparezcan (mismo
   estilo que las features #1-#14).
3. Actualizar el conteo de PASS esperado.

---

## 5. Plan de pruebas

Dos niveles, más la **regresión**.

### 5.1. Funciones puras (Node, extraídas de la página servida)

Se agregan casos al bloque que ya arma `verify-demo.ps1` (marcadores `// INICIO/FIN funciones
puras`). Ojo: nada de tildes en literales escritos; usar escapes (`'...'`) como ya se hace.

- `esSoloLectura`: `select`/`SELECT` con espacios → true; `insert`, `update`, `delete`, `drop`,
  `truncate`, `alter`, `create`, `merge`, `with`, `desc`, comando vacío → false.
- `claveDeTexto`: `'hql'` → clave HQL; `'sql'` → clave SQL; desconocido → clave HQL (default).
- `pasaFiltroTabla` / `filtrarTablas`:
  - filtro vacío muestra todo;
  - filtro por texto: coincide por substring, sin distinguir mayúsculas;
  - filtro por tipo: `Tablas` oculta vistas y viceversa; `Todas` muestra ambas;
  - combinación texto+tipo;
  - no muta la lista original;
  - lista vacía no explota.
- `normalizarFiltro`: recorta espacios y baja a minúsculas.

### 5.2. End-to-end (`verify-demo.ps1`)

El helper `Exec` se extiende para aceptar `language` (parámetro opcional) sin romper las llamadas
actuales. Casos nuevos:

**Lectura OK**
- `SELECT` crudo: `SELECT ID, TITULO FROM LIBROS` (nombres físicos) → 200, filas y headers.
- Un `SELECT` con `WHERE` y `ORDER BY` → 200.
- `SELECT` con comentarios `//`, `#`, `--` arriba y en el medio → 200.

**Bloqueo de escritura / DDL**
- `INSERT INTO ...` → 400.
- `UPDATE ...` → 400.
- `DELETE ...` → 400.
- `DROP TABLE ...`, `TRUNCATE ...`, `ALTER ...`, `CREATE ...`, `MERGE ...` → 400.
- Mensaje de error que diga que sólo se permite `SELECT` y la causa.
- (Regresión de seguridad) que un `DROP` **no** haya eliminado la tabla: un `SELECT` posterior
  sobre ella sigue funcionando.

**Varias sentencias**
- `SELECT ...; SELECT ...` en modo SQL → 400 ("una por vez").

**Tope de filas**
- Con `-MaxRows` chico: un `SELECT` sin `LIMIT` → `truncated=true`, `rowCount=maxRows`.
- Un `SELECT` con `LIMIT n` que entra en el tope → `truncated=false`.

**Metadata en modo SQL**
- `DESC` (modo SQL) lista tablas; **no** incluye `INFORMATION_SCHEMA` ni `PG_CATALOG`/`SYS`.
- `DESC` (modo SQL) de una tabla mapeada marca `PK` en la columna del id (por ejemplo `ID`) y `-` en
  el resto de las columnas.
- `DESC` (modo SQL) de una tabla con FK (por ejemplo `LIBROS` con `ID_AUTOR`) marca `FK` con el
  destino en formato `TABLA(CAMPO)` y `-` en las columnas que no son FK.
- Si hubiera una PK compuesta, **todas** sus columnas deben salir marcadas `PK`.
- La columna `NULO` sale `NO` en una columna NOT NULL y `SI` en una nullable (se elige un caso del
  demo que se conozca).
- Incluye una tabla conocida del demo (ej. `LIBROS`) y una vista (si el demo tiene una; si no, se
  agrega una al seed **sólo si es barato**, o se omite y se documenta).
- `DESC LIBROS` (modo SQL) trae headers de campos (`CAMPO`,`TIPO SQL`,...) y **no** `ATRIBUTO`.

**Persistencia / estructura de página**
- La página trae los dos `textarea` y las dos solapas.
- Aparecen las claves `hql-console.consulta-sql` y `hql-console.solapa`.
- `node --check` del JS servido sigue pasando.

### 5.3. Regresión (lo importante)

- **Correr `verify-demo.ps1` en los dos escenarios** y exigir:
  - sin context-path, `-MaxRows 500` → **196 PASS / 0 FAIL** (o el número vigente);
  - `-ContextPath /demo -MaxRows 3` → **203 PASS / 0 FAIL** (o el vigente).
- No se borra ni se relaja ningún test existente. Si alguno depende de la estructura del HTML y el
  feature la cambia, se **actualiza el test con justificación en el commit**, nunca se elimina.
- En particular, **no** se toca la regla CSS base `#panel-entidades { flex:0 0 auto; width:150px` ni la
  búsqueda literal `pedir('DESC', false)`, ni el texto del `<div class="barra">`, para no romper
  checks vigentes.

---

## 6. Compatibilidad con los tests existentes (chequeo previo a codear)

Antes de escribir la Fase 4, revisar que estos checks actuales sigan pasando:

| Check actual | Riesgo | Mitigación |
|---|---|---|
| `el encabezado solo dice HQL Console` | Meter las solapas dentro de `.barra` lo rompe | Poner las solapas en un contenedor aparte. |
| `#panel-entidades { flex:0 0 auto; width:150px` | Cambiar el ancho base lo rompe | Regla aparte para `body.modo-sql`, base intacta. |
| `la lista de entidades sale del DESC sin argumentos` (busca `pedir('DESC', false)`) | El modo SQL usa otra llamada | Mantener esa llamada en modo HQL (no se borra). |
| `el click de una entidad ejecuta su DESC` | Sigue existiendo en modo HQL | No tocar ese camino. |
| `el textarea no envuelve las lineas largas` (busca `id="hql"`) | El nuevo `#sql` también necesita `wrap="off"` | Ponerlo. |
| Persistencia (`CLAVE_TEXTO`) | Se agrega otra clave | La de HQL queda igual. |
| `node --check` del JS | El text block de Java rompe si una barra invertida va simple | Barras dobles; correr `node --check`. |

---

## 7. Riesgos y mitigaciones

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| **Límite de 65535 del template** | Alta | Alto (no compila) | **Fase 0**: medir y empezar por acá (externalizar CSS o partir en tres). |
| Escritura/DDL colándose como lectura | Baja | Crítico (destruye datos) | Whitelist `select` **en el backend**, no sólo en el cliente. Test e2e que verifica que el `DROP` falló y la tabla sigue. |
| Romper el modo HQL | Media | Alto | Sin ramas que toquen el camino actual; `language` default `"hql"`; regresión completa. |
| Panel de tablas ilegible | Alta | Medio | Esquema de la conexión + exclusión de catálogos + filtro de texto + filtro de tipo. |
| Portabilidad del SQL (`LIMIT`, comillas, funciones) | Media | Bajo | Es una consola de dev contra **la** base de la app: se acepta el dialecto real. Documentar. |
| Metadata de columnas (nombres/tipos) según el driver | Media | Medio | `ResultSetMetaData` con fallback a `getReturnTypes()`; si no hay metadata, headers `colN` y tipos `OTRO`. |
| Solapas que cambian el layout/foco | Baja | Bajo | Mantener el foco en el editor activo; recordar solapa; probar por estructura. |

---

## 8. Criterios de aceptación

1. El modo HQL funciona **exactamente** como antes (regresión en verde, sin tests eliminados).
2. Existe la solapa SQL, con su propio texto persistido y recordado.
3. En modo SQL sólo se ejecuta `SELECT`; cualquier otra sentencia da 400 con mensaje claro.
4. El panel izquierdo, en modo SQL, lista tablas/vistas del esquema con filtro por texto y por tipo.
5. `DESC` en modo SQL muestra `CAMPO`/`TIPO SQL`/`NULO`/`PK`/`FK`, aclarando **PK** (incluida la
   compuesta) y **FK con su tabla y campo destino**, sin `ATRIBUTO` ni navegación.
6. Los menús contextuales y "Generar INSERT" **no** aparecen en modo SQL.
7. Los comentarios siguen parseándose en modo SQL.
8. El tope `hql-console.max-rows` aplica al SQL nativo y avisa cuando trunca.
9. `verify-demo.ps1` corre en los dos escenarios con **0 FAIL**, y suma casos nuevos (unidad + e2e).
10. `node --check` del JS servido pasa (text block de Java sano).
11. `README.md` y `PENDIENTES.md` actualizados.

---

## 9. Estimación

| Fase | Trabajo |
|---|---|
| 0. Template | 2-4 horas (o media jornada si se externaliza el CSS) |
| 1. Idioma y validación | 1-2 horas |
| 2. Motor SQL nativo | 4-8 horas |
| 3. Tablas y `DESC` de campos | 4 horas |
| 4. Solapas y persistencia | 3-4 horas |
| 5. Panel con filtro | 3-4 horas |
| 6. Ejecución y modo SQL | 3-4 horas |
| 7. Tests y regresión | 4-6 horas |
| 8. Documentación | 1-2 horas |
| **Total** | **~1,5 a 2 jornadas** |

El grueso del riesgo está en la **Fase 0** (template) y en la **Fase 2** (motor nativo). Todo lo
demás son adaptaciones sobre piezas ya existentes.

---

## 10. Orden de commits sugerido

1. `Fase 0`: preparar el template (sin feature visible).
2. `Fase 1 + 2`: backend de SQL de lectura + validación (con tests e2e).
3. `Fase 3`: tablas y `DESC` de campos (con tests).
4. `Fase 4`: solapas y persistencia.
5. `Fase 5 + 6`: panel, filtro y ejecución en modo SQL.
6. `Fase 7 + 8`: cierre de tests, README y PENDIENTES.

Cada commit deja el árbol en verde (`verify-demo.ps1` con 0 FAIL).