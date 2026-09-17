package thejavalistener.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name="departamentos")
public class Departamento
{
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;

	@Column(nullable=false)
	private String nombre;

	protected Departamento()
	{
	}

	public Departamento(String nombre)
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
}
