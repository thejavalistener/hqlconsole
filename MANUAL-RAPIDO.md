# Manual rápido de la consola HQL

Abrí `http://localhost:8080/hqlconsole`. Es una herramienta de desarrollo: no
la dejes habilitada en producción.

## Consultar y descubrir

```hql
DESC Libro
SELECT l.id, l.titulo FROM Libro l ORDER BY l.titulo
SELECT * FROM Libro LIMIT 20
```

`DESC` muestra entidades y atributos Java. HQL usa esos nombres (`Libro`,
`titulo`), no los nombres físicos de tabla o columna. Ejecutá con `Ctrl+Enter`:
la consola corre la selección o el párrafo donde está el cursor.

## Insertar y actualizar

```hql
INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Nuevo libro', TODAY)
UPDATE Libro l SET l.titulo = 'Título corregido' WHERE l.id = 1
```

Separá sentencias de un mismo script con `;`. Se ejecutan en una sola
transacción: si una falla, se revierte todo el script.

## Fechas relativas

En los `INSERT ... VALUES` y `UPDATE ... SET` propios de la consola se aceptan
`NOW` y `TODAY` con días enteros:

```hql
INSERT INTO PromocionVigencia (fechaInicio, fechaFin)
VALUES (TODAY - 10, NOW + 5)
```

Sólo hay días: no meses ni años. No se aceptan expresiones como `NOW + -1` ni
`NOW + 1 + 2`. `NOW` sin aritmética conserva la conversión al tipo del campo.

## Variables de script

Una variable existe únicamente durante ese script. Puede recibir un ID generado
por `INSERT`:

```hql
$autor = INSERT INTO Autor (nombre) VALUES ('Ada');
INSERT INTO Libro (titulo, autor) VALUES ('Notas de Ada', $autor);
```

También puede recibir un `SELECT` HQL escalar de una sola fila y columna:

```hql
$fecha = SELECT l.fechaPublicacion FROM Libro l WHERE l.id = 1;
INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Edición posterior', $fecha + 10);
```

Se puede aplicar `+/-` un entero a fechas (días) y números. No hay listas,
entidades, `if`, bucles, concatenación ni variables que sobrevivan a otra
ejecución. Si el `SELECT` devuelve cero o más de una fila, más de una columna,
`NULL` o una entidad, el script falla y se revierte.

## Comentarios y límites

Los comentarios `//`, `#` y `--` se ignoran. Las consultas admiten `LIMIT n`;
además, `hql-console.max-rows` aplica un tope global. El modo SQL nativo es de
sola lectura: allí sólo se permite `SELECT`.
