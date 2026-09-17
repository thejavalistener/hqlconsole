package thejavalistener.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name="libros")
public class Libro
{
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;

	/** NOT NULL a propósito: sirve para ver qué pasa cuando un INSERT lo omite. */
	@Column(nullable=false,length=200)
	private String titulo;

	/** DATE: para probar que NOW se convierte a fecha sin hora. */
	private LocalDate fechaPublicacion;

	/** TIMESTAMP: para probar que NOW se convierte a fecha con hora. */
	private LocalDateTime fechaAlta;

	private LocalDateTime fechaModif;

	/** La columna se llama ID_AUTOR, como en el ejemplo de DESC. */
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name="id_autor")
	private Autor autor;

	@Column(precision=10,scale=2)
	private BigDecimal precio;

	private Boolean disponible;

	/**
	 * Sin setter a propósito: este campo lo tiene que asignar la consola por reflexión al atributo,
	 * que es el otro camino del binder.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length=20)
	private Genero genero;

	protected Libro()
	{
	}

	public Libro(String titulo,LocalDate fechaPublicacion,Autor autor,BigDecimal precio,Genero genero)
	{
		this.titulo=titulo;
		this.fechaPublicacion=fechaPublicacion;
		this.autor=autor;
		this.precio=precio;
		this.genero=genero;
	}

	public Long getId()
	{
		return id;
	}

	public String getTitulo()
	{
		return titulo;
	}

	public void setTitulo(String titulo)
	{
		this.titulo=titulo;
	}

	public LocalDate getFechaPublicacion()
	{
		return fechaPublicacion;
	}

	public void setFechaPublicacion(LocalDate fechaPublicacion)
	{
		this.fechaPublicacion=fechaPublicacion;
	}

	public LocalDateTime getFechaAlta()
	{
		return fechaAlta;
	}

	public void setFechaAlta(LocalDateTime fechaAlta)
	{
		this.fechaAlta=fechaAlta;
	}

	public LocalDateTime getFechaModif()
	{
		return fechaModif;
	}

	public void setFechaModif(LocalDateTime fechaModif)
	{
		this.fechaModif=fechaModif;
	}

	public Autor getAutor()
	{
		return autor;
	}

	public void setAutor(Autor autor)
	{
		this.autor=autor;
	}

	public BigDecimal getPrecio()
	{
		return precio;
	}

	public void setPrecio(BigDecimal precio)
	{
		this.precio=precio;
	}

	public Boolean getDisponible()
	{
		return disponible;
	}

	public void setDisponible(Boolean disponible)
	{
		this.disponible=disponible;
	}

	public Genero getGenero()
	{
		return genero;
	}
}
