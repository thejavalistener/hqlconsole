package thejavalistener.demo;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * Aplicación de ejemplo: una app Spring Boot común y corriente, con JPA y H2 en memoria.
 *
 * <p>Lo único que hace para tener consola HQL es declarar la dependencia en
 * {@code build.gradle}. No hay configuración, no hay imports, no hay anotaciones.</p>
 */
@SpringBootApplication
public class HqlConsoleDemoApplication
{
	public static void main(String[] args)
	{
		SpringApplication.run(HqlConsoleDemoApplication.class,args);
	}

	@Bean
	CommandLineRunner seed(EntityManagerFactory entityManagerFactory)
	{
		return args -> {
			EntityManager em=entityManagerFactory.createEntityManager();
			EntityTransaction tx=em.getTransaction();
			tx.begin();

			Departamento sistemas=new Departamento("Sistemas");
			Departamento compras=new Departamento("Compras");
			Departamento ventas=new Departamento("Ventas");
			em.persist(sistemas);
			em.persist(compras);
			em.persist(ventas);

			em.persist(new Empleado("Ana Gomez",new BigDecimal("1500000.50"),LocalDate.of(2019,3,1),sistemas));
			em.persist(new Empleado("Bruno Diaz",new BigDecimal("1200000.00"),LocalDate.of(2021,7,15),sistemas));
			em.persist(new Empleado("Carla Ruiz",new BigDecimal("980000.75"),LocalDate.of(2022,1,10),compras));
			em.persist(new Empleado("Diego Sosa",new BigDecimal("2100000.00"),LocalDate.of(2017,11,20),ventas));
			em.persist(new Empleado("Elena Paz",new BigDecimal("1150000.00"),LocalDate.of(2023,5,2),null));
			em.persist(new Empleado("Fabio Luna",new BigDecimal("1350000.25"),LocalDate.of(2020,9,30),null));

			Autor borges=new Autor("Jorge Luis Borges");
			Autor cortazar=new Autor("Julio Cortazar");
			Autor saer=new Autor("Juan Jose Saer");
			em.persist(borges);
			em.persist(cortazar);
			em.persist(saer);

			em.persist(new Libro("Ficciones",LocalDate.of(1944,1,1),borges,new BigDecimal("15000.00"),Genero.NOVELA));
			em.persist(new Libro("El Aleph",LocalDate.of(1949,6,1),borges,new BigDecimal("18000.50"),Genero.NOVELA));
			em.persist(new Libro("Rayuela",LocalDate.of(1963,6,28),cortazar,new BigDecimal("22000.00"),Genero.NOVELA));
			em.persist(new Libro("El astillero",LocalDate.of(1961,1,1),null,new BigDecimal("12000.00"),Genero.NOVELA));
			em.persist(new Libro("Zama",LocalDate.of(1956,1,1),saer,null,Genero.NOVELA));
			em.persist(new Libro("Sin autor conocido",LocalDate.of(2000,1,1),null,null,null));

			tx.commit();
			em.close();
		};
	}
}
