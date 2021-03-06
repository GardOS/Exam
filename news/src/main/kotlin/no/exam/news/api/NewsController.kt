package no.exam.news.api

import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import no.exam.news.model.News
import no.exam.news.model.NewsConverter
import no.exam.news.model.NewsRepository
import no.exam.schema.BookDto
import no.exam.schema.NewsDto
import no.exam.schema.SaleDto
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionSystemException
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import javax.servlet.http.HttpServletResponse
import org.hibernate.exception.ConstraintViolationException as HibernateConstraintViolationException
import javax.validation.ConstraintViolationException as JavaxConstraintViolationException

@Api(value = "/news", description = "API for news")
@RequestMapping(
		path = ["/news"],
		produces = [(MediaType.APPLICATION_JSON_VALUE)]
)
@RestController
@Validated
class NewsController {
	@Autowired
	private lateinit var restTemplate: RestTemplate

	@Autowired
	private lateinit var newsRepo: NewsRepository

	@Value("\${bookServerPath}")
	private lateinit var bookServerPath: String

	//RABBIT
	@RabbitListener(queues = ["#{saleCreatedQueue.name}"])
	fun saleCreatedEvent(sale: SaleDto) {
		try {
			//Find book
			val book: BookDto = try {
				restTemplate.getForObject("$bookServerPath/${sale.book}", BookDto::class.java)
			} catch (ex: Exception) {
				BookDto(title = "Book title not found")
			}

			newsRepo.save(
					News(
							sale = sale.id,
							sellerName = sale.seller,
							bookTitle = book.title,
							bookPrice = sale.price,
							bookCondition = sale.condition
					)
			)
		} catch (ex: Exception) {
		}
	}

	@RabbitListener(queues = ["#{saleUpdatedQueue.name}"])
	fun saleUpdatedEvent(sale: SaleDto) {
		try {
			val news = newsRepo.findOne(sale.id)
			if (sale.price != null)
				news.bookPrice = sale.price

			if (sale.condition != null)
				news.bookCondition = sale.condition

			newsRepo.save(news)
		} catch (ex: Exception) {
		}
	}

	@RabbitListener(queues = ["#{saleDeletedQueue.name}"])
	fun saleDeletedEvent(sale: SaleDto) {
		try {
			newsRepo.delete(sale.id)
		} catch (ex: Exception) {
		}
	}

	//GET ALL
	@ApiOperation("Get all the news, add \"getLatest\" flag for the ten latest news")
	@GetMapping
	fun getAllNews(
			@RequestParam(name = "getLatest", required = false, defaultValue = "false")
			getLatest: Boolean
	): ResponseEntity<List<NewsDto>> {
		val news = newsRepo.findAll()

		if (getLatest)
			return ResponseEntity.ok(NewsConverter.transform(news.toList().takeLast(10).asReversed()))

		return ResponseEntity.ok(NewsConverter.transform(news))
	}

	//Catches validation errors and returns error status based on error
	@ExceptionHandler(value = ([JavaxConstraintViolationException::class, HibernateConstraintViolationException::class,
		DataIntegrityViolationException::class, TransactionSystemException::class]))
	fun handleValidationFailure(ex: Exception, response: HttpServletResponse): String {
		var cause: Throwable? = ex
		for (i in 0..4) { //Iterate 5 times max, since it might have infinite depth
			if (cause is JavaxConstraintViolationException || cause is HibernateConstraintViolationException) {
				response.status = HttpStatus.BAD_REQUEST.value()
				return "Invalid request"
			}
			cause = cause?.cause
		}
		response.status = HttpStatus.INTERNAL_SERVER_ERROR.value()
		return "Something went wrong processing the request"
	}
}