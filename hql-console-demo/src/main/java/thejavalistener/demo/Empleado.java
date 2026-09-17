package thejavalistener.demo;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name="empleados")
public class Empleado
{
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;

	@Column(nullable=false)
	private String nombre;

	private BigDecimal salario;

	private LocalDate ingreso;

	/** Lazy a propósito: sirve para comprobar que la consola no inicializa proxies al mostrar filas. */
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name="departamento_id")
	private Departamento departamento;

	protected Empleado()
	{
	}

	public Empleado(String nombre,BigDecimal salario,LocalDate ingreso,Departamento departamento)
	{
		this.nombre=nombre;
		this.salario=salario;
		this.ingreso=ingreso;
		this.departamento=departamento;
	}

	public Long getId()
	{
		return id;
	}

	public String getNombre()
	{
		return nombre;
	}

	public BigDecimal getSalario()
	{
		return salario;
	}

	public LocalDate getIngreso()
	{
		return ingreso;
	}

	public Departamento getDepartamento()
	{
		return departamento;
	}
}
