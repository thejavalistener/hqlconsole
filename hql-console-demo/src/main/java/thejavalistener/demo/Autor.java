package thejavalistener.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name="autores")
public class Autor
{
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;

	@Column(nullable=false,length=80)
	private String nombre;

	protected Autor()
	{
	}

	public Autor(String nombre)
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

	/** Texto corto que la HQL Console puede mostrar junto al id de una relación cargada. */
	public String toHqlConsoleString()
	{
		return nombre;
	}
}
