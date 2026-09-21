# Features acordadas: registro de decisiones

Este archivo es el registro de las features que se acordaron para la consola, con el **por qué** de
cada decisión y las **trampas** que aparecieron. Está escrito para que cualquiera que retome el
trabajo (una sesión nueva, otro modelo) no tenga que adivinar: la documentación de usuario está en el
`README.md` y la verificación en `verify-demo.ps1`.

## Estado

| Feature | Estado |
|---|---|
| #1 `DESC` con `ATRIBUTO` y `TIPO JAVA` primero | **hecho** |
| #2 `INSERT INTO Entidad (campoRel1, campoRel2) VALUES (1, 1)` | **ya funcionaba**; ahora tiene test |
| #3 `SELECT * FROM Entidad` equivalente a `FROM Entidad` | **hecho** |
| #4 `LIMIT n` al final de las consultas | **hecho** |
| #5 panel lateral de entidades, colapsable | **hecho** |

Verificación: `verify-demo.ps1` → **172 PASS / 0 FAIL**; con `-ContextPath /demo -MaxRows 3` →
**179 PASS / 0 FAIL** (venía de 144/148). Las cuatro features están cubiertas end-to-end; el clic y
el layout del panel en sí no los ve ningún test automático, eso se mira en el navegador.

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
