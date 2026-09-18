package thejavalistener.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Existe por una sola razón: probar la convención de mayúsculas de la consola.
 *
 * <p>El nombre de la tabla tiene mayúsculas mezcladas <b>a propósito</b>. En SQL un identificador así
 * sólo existe si está entrecomillado, y la consola lo muestra tal cual en {@code DESC} —pasarlo a
 * mayúsculas apuntaría a otra tabla—. Las demás tablas del demo están en minúsculas y la consola las
 * muestra en MAYÚSCULAS, que es la convención para que la misma aplicación se vea igual en H2 (que
 * guarda los identificadores sin comillas en mayúsculas) y en Postgres (que los guarda en
 * minúsculas).</p>
 */
@Entity
@Table(name="EtiquetaRara")
public class Etiqueta
{
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;

	@Column(nullable=false)
	private String nombre;

	protected Etiqueta()
	{
	}

	public Etiqueta(String nombre)
	{
		this.nombre=nombre;
	}

	public Long getId()
	{
		return id;
	}

	public String getNombre()
	{
		return nombre;
	}

	public void setNombre(String nombre)
	{
		this.nombre=nombre;
	}
}
