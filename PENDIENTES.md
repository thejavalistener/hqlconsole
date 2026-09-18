# Features acordadas (pendientes de implementar)

Este archivo es la especificación de las features que quedaron acordadas para la consola. Está
escrito para que **cualquiera que retome el trabajo** (una sesión nueva, otro modelo) no tenga que
adivinar las decisiones: cada punto dice qué se hace y, cuando importa, qué **no** se hace.

Cuando todo esté implementado y verificado, este archivo se borra o se funde en el `README.md`.

## Estado

| Feature | Estado |
|---|---|
| #2 alert de INSERT con el conteo | **hecho** |
| #4.2 INSERT sin alias (`VALUES campo=valor`) | **hecho** (ya funcionaba; ahora tiene tests) |
| #4.3 INSERT posicional (`(columnas) VALUES (valores)`) | **hecho** |
| #5 lotes de INSERT separados por `;` | **hecho** |
| #1 dry-run + confirmación en UPDATE/DELETE | **hecho** |

`verify-demo.ps1`: **125 PASS / 0 FAIL** (129 con `-ContextPath /demo -MaxRows 3`); venía de 86.

La documentación de usuario de todo esto ya está en el `README.md` (los tres formatos de INSERT, los
lotes, el alert y la confirmación). **Este archivo ya cumplió su función**: se puede borrar, o dejar
como registro de las decisiones y de las trampas de abajo.


## Orden de implementación

1. **#2** alert de INSERT + **#4.2** test del INSERT sin alias
2. **#4.3** `INSERT INTO X (atributos) VALUES (valores)`
3. **#5** lotes de INSERT separados por `;`
4. **#1** dry-run con confirmación en UPDATE y DELETE

El #1 va último a propósito: es el único que toca el manejo transaccional, así que si algo se rompe
se sabe que fue eso.

## #1 — Confirmación antes de commitear (UPDATE y DELETE)

**Implementado.** Lo único que cambió en el motor fue sacar la decisión de cerrar la transacción a
un método aparte (`_cerrar(tx, dryRun)`): con dry-run hace `rollback` donde haría `commit`. El trabajo
es el mismo, así que el número que se muestra es el real. La página manda dos requests
(`dryRun: true` y después `dryRun: false`) y el flag lo lee el controller.

**Bug que apareció al implementarlo:** el `UPDATE` truncado avisaba del tope sólo en el `message`,
pero el campo `truncated` del JSON venía **siempre `false`** (estaba fijo en la fábrica `dml(...)`).
Como el aviso de la página lee ese campo, el "se alcanzó el tope, el resto NO se toca" nunca se
habría mostrado. Se arregló con una sobrecarga de `dml(...)` que acepta `truncated`, y ahora hay dos
chequeos que lo fijan.

**Motivación:** evitar romper todo cuando se quiere modificar o borrar **una sola fila**. El conteo
es la alarma: si esperás 1 fila y el alert dice 4, cancelás.

**Diseño elegido: dry-run (opción c).** Dos requests, sin transacciones abiertas entre medio:

```
request 1: { hql, dryRun: true }   → ejecuta, cuenta, hace ROLLBACK y cierra
                                     devuelve { affectedRows: N, dryRun: true }
   la página muestra: "Se van a borrar 4 fila(s). ¿Confirmás?"  [Aceptar] [Cancelar]
request 2: { hql, dryRun: false }  → ejecuta y COMMITEA
```

- **Automático, no opcional:** la página hace el dry-run sola para todo UPDATE y DELETE. El usuario
  no tiene que activar nada.
- El alert muestra el número, y si el tope de filas (`maxRows`) truncó, lo dice.
- Después de confirmar, el resultado muestra el número **real**.
- **NO** se muestran los ids de las filas afectadas: el DELETE es un `executeUpdate` en bloque que no
  las conoce, y cargarlas cambiaría ese camino. El conteo cubre el caso.
- **Se descartó** la variante con la transacción abierta entre los dos requests: contradice el diseño
  documentado en `HqlQueryRunner` ("un EntityManager nuevo por sentencia, cada sentencia aislada"),
  exige guardar EntityManagers pendientes con vencimiento, y mantiene filas bloqueadas mientras el
  alert está en pantalla.
- **Costo aceptado:** la sentencia se ejecuta dos veces. Los listeners `@PreUpdate`/`@PreRemove` con
  efectos por fuera de la transacción (mails, auditoría) se disparan dos veces.

## #2 — Alert al insertar

- Texto: **"Se insertó 1 fila"** (singular) / **"Se insertaron N filas"** (plural).
- Con lote, se agrega en cuántas sentencias: **"Se insertaron 5 filas en 4 sentencias"**.
- La detección de "esto fue un INSERT" es del lado de la página (el texto ejecutado empieza con
  `insert`), no del backend: así no hay que tocar el contrato JSON.

## #3 — DESC sin argumentos

**No se toca.** Ya devuelve una grilla con `ENTIDAD | TABLA | CAMPOS`.

## #4 — Los tres formatos de INSERT

Los tres tienen que funcionar y convivir:

```sql
-- 4.1 con alias (ya funciona)
INSERT INTO Libro li VALUES li.titulo='Uno', li.precio=100

-- 4.2 sin alias (debería andar; falta el test)
INSERT INTO Libro VALUES titulo='Dos', precio=200

-- 4.3 clásico posicional (nuevo)
INSERT INTO Libro (titulo, precio) VALUES ('Tres', 300)
```

- En **4.3** los nombres entre paréntesis son **atributos de la clase** (`fechaPublicacion`), no
  columnas físicas (`FECHA_PUBLICACION`). Coherente con 4.1/4.2 y con la columna `ATRIBUTO` de `DESC`.
- **Cuidado:** `INSERT INTO Libro (titulo) SELECT ...` es HQL de verdad y tiene un test que lo cubre.
  Después del `)` hay que distinguir `VALUES` (gramática de la consola) de `SELECT` (seguir por
  Hibernate).
- Si la cantidad de columnas y de valores no coincide → error claro.
- **NO** se implementa el multi-fila clásico (`VALUES (...), (...), (...)`): descartado a propósito.

### Hallazgo: el camino de HQL se come lo que la consola rechaza

Cuando el parser de la consola **falla**, el runner igual prueba la sentencia como HQL, y sólo si
Hibernate también falla reporta el error de la consola. Consecuencia: hay sentencias que la consola
no entiende pero **Hibernate sí**, y entonces funcionan sin pasar por la gramática de la consola:

| Sentencia | Qué pasa |
|---|---|
| `INSERT INTO Libro (titulo, precio) VALUES ('a',1), ('b',2)` | la ejecuta Hibernate (multi-fila) |
| `INSERT INTO Libro li (titulo) VALUES ('x')` | la ejecuta Hibernate (alias + columnas) |

Por eso el parser **devuelve `null`** en esos dos casos (en vez de tirar un error): así termina de
resolverlo Hibernate, que es lo que ya hacía antes de la feature 4.3.

**Los dos caminos no son equivalentes**, y esto hay que saberlo:

- El camino de la consola hace `em.persist(...)`: dispara `@PrePersist`, `@Version` y la validación,
  y entiende `NOW`, los enums y las relaciones por id.
- El camino de HQL es un **bulk**: no pasa por el contexto de persistencia, así que **no dispara
  `@PrePersist` ni valida**, y **no conoce `NOW`** (HQL usa `current_timestamp`).

O sea: `VALUES ('a', NOW)` de una fila funciona, y el mismo `NOW` en un multi-fila **no**. Para
cargar datos de prueba, el camino recomendado es el lote con `;` (feature #5), porque cada sentencia
entra por la consola y conserva las conversiones.

## #5 — Varias sentencias con `;`

**Sólo INSERT.** Es para dar de alta datos de prueba.

**El corte va ANTES del parseo**, en una capa superior (no dentro del parser de INSERT). Por eso los
tres formatos funcionan en un lote sin trabajo extra: cada trozo entra por el mismo camino que una
sentencia sola.

Reglas:

- **Una sola sentencia** (con o sin `;` al final) → se comporta como hoy: `SELECT`, `INSERT`,
  `UPDATE`, `DELETE`, `DESC`, todo permitido. Esto es importante: si la política "sólo INSERT"
  aplicara a lotes de tamaño 1, un `SELECT ...;` quedaría rechazado.
- **Más de una** → es un lote, y sólo INSERT. Si no lo es, error que diga **en qué posición**:
  *"La sentencia 2 de 4 no es un INSERT: un lote sólo sirve para dar de alta datos."*
- **Una sola transacción para todo el lote**: si la tercera falla, no se insertó nada.
- Se tolera un `;` final y se saltean las sentencias vacías (`;;`).
- Un `;` dentro de un texto (`li.titulo='a;b'`) **no** corta: el escáner ya entiende comillas.
- El chequeo de `allow-writes` mira **todas** las sentencias, no la primera.
- Alert único al final: **"Se insertaron 5 filas en 4 sentencias"**.
- La respuesta de una sentencia sola **no cambia**: los 86 chequeos de `verify-demo.ps1` dependen de
  esa forma. El lote usa un tipo nuevo (por ejemplo `BATCH`).

### Bug que esto arregla de paso

Hoy, un `;` final en una sentencia de la consola rompe de dos maneras distintas:

```sql
INSERT INTO Libro li VALUES li.titulo='Un titulo';
```

Al conversor le llega el literal `'Un titulo';`, y como `Text.isQuoted` exige que el **último**
carácter sea `'`, no lo reconoce como texto citado; para un campo `String` devuelve el valor tal
cual, así que **guarda la cadena `'Un titulo';`** con comillas y punto y coma incluidos
(**silencioso**). Con un número (`li.precio=100;`) falla, porque `Integer.valueOf("100;")` explota.

Con el corte antes del parseo, el `;` final desaparece y queda arreglado. Agregar un chequeo.

## #6 — `dist/` y `publish.ps1`

**Sin efecto.** Queda como está: `dist/hql-console-starter.jar` se versiona y el workflow de release
sigue igual.

## Trampas del código que hay que respetar

- **El JS de la página vive en un text block de Java.** Una barra invertida va **doble** (`\\n`), o
  Java la convierte en un salto de línea real y **rompe el JavaScript sin que falle la compilación**.
  Pasó dos veces durante el desarrollo de las features anteriores.
- **Las funciones puras** (sin DOM) van entre los marcadores `// INICIO funciones puras` y
  `// FIN funciones puras`: `verify-demo.ps1` extrae ese bloque del HTML **que sirve el jar** y lo
  corre en Node. Si agregás una función pura, ponela ahí y agregale casos.
- **En `verify-demo.ps1` no uses tildes en los patrones ni en los literales escritos**: PowerShell
  5.1 lee el archivo como ANSI si no tiene BOM, así que se comparan mal. Para texto con tilde en los
  tests de Node, usá escapes (`'Se insert\u00f3 1 fila'`).
- **Los comentarios y la documentación van en español**, igual que el resto del proyecto.

## Lo que se decidió NO hacer

- Multi-fila clásico (`VALUES (...), (...)`).
- Mostrar los ids de las filas afectadas en el dry-run.
- Confirmación para INSERT (sólo avisa).
- Tocar `DESC` sin argumentos.
- Comandos `;` para sentencias que no sean INSERT (por ahora).
