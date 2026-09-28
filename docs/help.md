# Manual rápido de HQL Console

La consola ejecuta HQL contra el `EntityManager` vivo de la aplicación. Es una
herramienta de desarrollo: no la dejes habilitada en producción.

## Consultar y descubrir

Usa los nombres de entidades y atributos Java, no las tablas ni columnas
físicas. `Ctrl+Enter` ejecuta la selección o el párrafo donde está el cursor.

```hql
DESC Libro
SELECT l.id, l.titulo FROM Libro l ORDER BY l.titulo
SELECT * FROM Libro LIMIT 20
FROM Libro l WHERE l.autor.nombre LIKE '%Borges%'
```

## Insertar y actualizar

Las sentencias DELETE y UPDATE son propias de HQL. INSERT solo existe en la consola y permite insertar filas (crear objetos). Separa las sentencias de un script con `;`. Se ejecutan en una sola transacción: si
una falla, se revierte todo el script.

```hql
INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Nuevo libro', TODAY)
UPDATE Libro l SET l.titulo = 'Título corregido' WHERE l.id = 1
DELETE Libro l WHERE l.id=1
```

## NOW y TODAY

`NOW` y `TODAY` son sinónimos y soportan aritmética temporal dentro de la consola. Ambos
representan la fecha actual; se les pueden sumar o restar días enteros. No hay soporte para meses ni años.

```hql
INSERT INTO PromocionVigencia (fechaInicio, fechaFin) VALUES (TODAY - 10, TODAY + 5)
```

## Literales y variables

```hql
$fecha = '2026-05-19';
$valorInt = 10;
$importe = 1250.50;
$activo = true;

INSERT INTO Libro (titulo, fechaPublicacion, precio) VALUES ('Edición posterior', $fecha + 10, $valorInt);
```

- Una fecha entre comillas usa ISO estricto `yyyy-MM-dd`.
- Un entero, decimal o booleano conserva su tipo escalar.
- `'2026-5-19'` se rechaza: escribí `'2026-05-19'`.
- Para forzar texto con forma de fecha, usá `TEXT '2026-05-19'`.

También se pueden usar IDs generados y un `SELECT` escalar de una sola fila y
columna:

```hql
$autor = INSERT INTO Autor (nombre) VALUES ('Ada');
INSERT INTO Libro (titulo, autor) VALUES ('Notas de Ada', $autor);

$fecha = SELECT l.fechaPublicacion FROM Libro l WHERE l.id = 1;
INSERT INTO Libro (titulo, fechaPublicacion) VALUES ('Edición posterior', $fecha + 10);
```

> Las variables sólo existen durante el script. No hay listas, entidades,
> `if`, bucles ni concatenación. Un error revierte todas las escrituras del
> script.

## Comentarios y límites

Se aceptan comentarios `//`, `#` y `--`. Las consultas aceptan `LIMIT n` y

## Valores foráneos

Para personalizar el valor de una clave foránea en una entidad (ej. Producto → Categoria), implementa el método `toHqlConsoleString` en la clase relacionada: 

```Java
public class Categoria
{
   public String toHqlConsoleString()
   {
      return descripcion;
   }
}
```

