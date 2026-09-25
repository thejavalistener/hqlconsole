# Pendiente: variables para IDs generados en lotes

## Problema

Las entidades mapeadas con `@GeneratedValue` delegan el ID a la base. Un script de carga que escribe
una FK con un número fijo (`categoria=1`) depende de que el contador identity esté en ese valor. Un
`DELETE` no reinicia necesariamente dicho contador, por lo que el script deja de ser repetible y puede
fallar por FK.

Forzar el `@Id` desde el `INSERT` actual tampoco es una solución: Hibernate recibe una entidad con ID
generado no nulo y puede tratarla como detached.

## Decisión

Incorporar variables efímeras, válidas sólo durante un lote, capturando el identificador devuelto por
el `INSERT` propio de la consola:

```sql
$categoriaComputacion = INSERT INTO Categoria (descripcion) VALUES ('Computación');
INSERT INTO Producto (descripcion, categoria, precioUnitario)
VALUES ('Notebook', $categoriaComputacion, 1300);
```

- La declaración es exclusivamente `$nombre = INSERT ...`; no se agrega `LET`.
- Una referencia es `$nombre` y puede ocupar cualquier literal que el binder convierta, en especial
  una relación `@ManyToOne`.
- Las variables viven sólo en la ejecución actual del lote; nunca quedan en sesión, navegador ni base.
- El lote conserva su transacción única: si una sentencia falla, se hace rollback y no se expone ni
  conserva ningún valor.
- Sólo aplica a `INSERT ... VALUES` de la consola, que materializa la entidad y conoce su ID. No aplica
  a HQL bulk `INSERT ... SELECT`.
- Deben producirse errores claros para variable indefinida, redefinición y uso fuera de un lote.

## Implementación y pruebas pendientes

El plan del lote debe conservar su mapa ordenado de variables. Después de cada `_insert`, toma el ID
que hoy ya se usa para el mensaje `Insertado Entidad#id`. Antes de ejecutar se valida la forma de la
asignación; durante el bind se sustituye `$nombre` por su valor.

Agregar pruebas de parser/plan para declaración, referencia, variable indefinida, redefinición y
alcance. Agregar una prueba end-to-end con entidad padre e hija que ejecute el lote dos veces sin
depender de IDs fijos.
