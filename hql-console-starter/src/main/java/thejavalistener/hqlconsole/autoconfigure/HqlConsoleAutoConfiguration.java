package thejavalistener.hqlconsole.autoconfigure;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import thejavalistener.hqlconsole.engine.EntityDescriber;
import thejavalistener.hqlconsole.engine.HqlQueryRunner;
import thejavalistener.hqlconsole.web.HqlConsoleController;

/**
 * Auto-configuración de la consola HQL.
 *
 * <p>Se descubre sola por estar nombrada en
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} dentro
 * del jar: la aplicación que la aloja no tiene que hacer {@code @Import} ni escanear el paquete.
 * Por eso acá no hay {@code @ComponentScan}: los beans se declaran uno por uno, como manda la
 * documentación de starters.</p>
 *
 * <p>El orden se declara <b>por nombre</b> ({@code afterName}), no por clase: si se referenciara
 * {@code HibernateJpaAutoConfiguration.class}, el jar quedaría atado a la ubicación de esa clase,
 * que Spring Boot 4 ya reorganizó en starters modulares. Igual el runner recibe el
 * {@code EntityManagerFactory} como {@link ObjectProvider}, o sea que se resuelve en cada consulta
 * y no en el arranque: una aplicación sin JPA ve un error claro en vez de un fallo de arranque, y
 * el orden de las auto-configuraciones deja de ser crítico.</p>
 */
@AutoConfiguration(afterName="org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
@ConditionalOnClass(EntityManager.class)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix="hql-console",name="enabled",havingValue="true",matchIfMissing=true)
@EnableConfigurationProperties(HqlConsoleProperties.class)
public class HqlConsoleAutoConfiguration
{
	/** El DataSource es opcional: sin él, DESC deriva los tipos del mapping en vez de la base. */
	@Bean
	@ConditionalOnMissingBean
	public EntityDescriber hqlConsoleEntityDescriber(ObjectProvider<DataSource> dataSource)
	{
		return new EntityDescriber(dataSource);
	}

	@Bean
	@ConditionalOnMissingBean
	public HqlQueryRunner hqlConsoleQueryRunner(ObjectProvider<EntityManagerFactory> entityManagerFactory,
	                                            EntityDescriber describer,HqlConsoleProperties properties)
	{
		return new HqlQueryRunner(entityManagerFactory,describer,properties.getMaxRows());
	}

	@Bean
	@ConditionalOnMissingBean
	public HqlConsoleController hqlConsoleController(HqlQueryRunner runner,HqlConsoleProperties properties)
	{
		return new HqlConsoleController(runner,properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public HqlConsoleBanner hqlConsoleBanner(Environment environment,HqlConsoleProperties properties)
	{
		return new HqlConsoleBanner(environment,properties);
	}
}
