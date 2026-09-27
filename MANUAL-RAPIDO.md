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

`NOW` y `TODAY` son sinónimos en esta aritmética: ambos toman la fecha de
inicio de la ejecución actual.

Sólo hay días: no meses ni años. No se aceptan expresiones como `NOW + -1` ni
`NOW + 1 + 2`. `NOW` sin aritmética conserva la conversión al tipo del campo.

## Variables de script

Una variable existe únicamente durante ese script. Puede declararse con
literales; una fecha entre comillas usa ISO estricto `yyyy-MM-dd` y se guarda
como fecha, los enteros/decimales conservan tipo numérico y los booleanos usan
`true` o `false`:

```hql
$fecha = '2026-05-19';
$valorInt = 10;
$importe = 1250.50;
$activo = true;

INSERT INTO Libro (titulo, fechaPublicacion, precio)
VALUES ('Edición posterior', $fecha + 10, $valorInt);
```

`'2026-5-19'` se rechaza: escribí `'2026-05-19'`. Un texto que tenga forma de
fecha se puede forzar como texto con `TEXT '2026-05-19'`.

También puede recibir un ID generado por `INSERT`:

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

## Datos foráneos o relacionales

La consola puede mostrar datos que provienen de las relaciones @ManyToOne. Por ejemplo, si vemos una fila de `Producto`, ver también nombre del `Proveedor`: 

| idProducto| proveedor|
|-----------|----------|
|          1|  3 (Sony)|

Para esto, se debe declarar en la clase el hijo (`Producto`) el método: `public String toHqlConsoleString() `. Por ejemplo:

```
public class Producto
{
   // :

   @ManyToOne
   private Proveedor proveedor;

   // :

   public String toHqlConsoleString()
   {
      return proveedor.getDescripcion();
   }

   // :
}
```