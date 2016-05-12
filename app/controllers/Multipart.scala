package controllers

import scala.concurrent._
import scala.concurrent.duration._
import akka.actor._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import java.io.ByteArrayInputStream
import org.apache.commons.io.FileUtils
import play.api._
import play.api.mvc._
import play.api.libs.Files._
import scala.util.{Try, Success => TrySuccess, Failure => TryFailure}
import es.weso.rdf._
import es.weso.rdf.nodes.IRI
import es.weso.rdf.jena._
import es.weso.monads.{Result => SchemaResult, Failure => SchemaFailure, Passed}
import es.weso.utils._
import es.weso.utils.TryUtils._
// import es.weso.utils.RDFUtils._
import es.weso.utils.IOUtils._
import es.weso.shex._
import es.weso.schema._
import java.net.URL
import java.io.File
import play.api.Logger

object Multipart {

   def getValidationForm(request: Request[AnyContent]): Try[ValidationForm] = {
    for ( mf <- getMultipartForm(request)
        ; inputData <- parseInputData(mf)
        ; opt_data <- parseOptData(mf)
        ; opt_schema <- parseOptSchema(mf)
        )
    yield {
     val has_schema = opt_schema.isDefined
     val input_schema = if (has_schema) 
              opt_schema.get._1 
             else 
                SchemaInput()

     val opts_schema = if (has_schema) 
              opt_schema.get._2 
              else 
                SchemaOptions.default
      ValidationForm(inputData, opt_data, has_schema, input_schema, opts_schema)
    }
  }

  def getValidationFormSchema(request: Request[AnyContent]): Try[ValidationForm] = {
    for ( mf <- getMultipartForm(request)
        ; inputSchema <- parseSchemaInput(mf)
        ; optsSchema <- parseSchemaOptions(mf)
        )
    yield {
      ValidationForm(DataInput(), DataOptions.default,true, inputSchema, optsSchema)
    }
  }
  
  def getMultipartForm(request: Request[AnyContent]): Try[MultipartFormData[TemporaryFile]] = {
    val body: AnyContent = request.body
   body.asMultipartFormData match {
      case Some(mf) => TrySuccess(mf)
      case None => TryFailure(new Exception("Expecting MultiformData request body"))
    }
  }

  
  def parseFile(mf: MultipartFormData[TemporaryFile], key:String) : Try[Option[File]] = {
    mf.file(key) match {
         case Some(f) => TrySuccess(Some(f.ref.file))
         case None => {
          // Failure(throw new Exception("File " + key + " not found in multipart form data")) 
          TrySuccess(None) 
         }
    }
  }
  
  def parseOptIRI(mf: MultipartFormData[TemporaryFile]): Try[Option[IRI]] = {
    val withIRI = parseWithIRI(mf)
    withIRI match {
      case TrySuccess(true) => {
        parseIRI(mf) match {
            case TrySuccess(iri) => TrySuccess(Some(iri))
            case TryFailure(e) => TryFailure(e)
          }
      }
      case TrySuccess(false) => TrySuccess(None)
      case TryFailure(e) => TryFailure(e)  
    }
  }

  def parseWithIRI(mf: MultipartFormData[TemporaryFile]): Try[Boolean] = {
    for (value <- parseKey(mf,"withIRI")) yield {
      if (value.startsWith("#noIri")) false
      else if (value.startsWith("#iri")) true
      else throw new Exception("parseWithIRI: unknown value " + value)
    }
  }

  def parseIRI(mf: MultipartFormData[TemporaryFile]): Try[IRI] = {
    for (value <- parseKey(mf,"iri")) yield IRI(value)
  }

  def parseInputData(mf: MultipartFormData[TemporaryFile]): Try[DataInput] = {
   for ( input_type_data <- parseInputType(mf,"data")
       ; data_uri <- parseKeyOrElse(mf,"data_uri","")
       ; data_textarea <- parseKeyOrElse(mf,"data_textarea","")
       ; data_file <- parseFile(mf,"data_file")
//       ; data_endpoint <- parseKey(mf,"data_endpoint")
       ) yield {
     DataInput(input_type_data, data_uri, data_file, data_textarea)
   }
  }
  
  def parseOptData(mf: MultipartFormData[TemporaryFile]): Try[DataOptions] = {
    for ( showData <- parseBoolean(mf,"showData")
        ; data_format <- parseKey(mf,"data_format")
        ) yield 
        DataOptions(format = data_format, 
                    showData = showData)
   }

  def parseOptSchema(mf: MultipartFormData[TemporaryFile]): Try[Option[(SchemaInput,SchemaOptions)]] = {
    for (value <- parseKey(mf,"schema")) yield {
      value match {
        case "#schema" => {
          val opts = 
            for ( input <- parseSchemaInput(mf)
                ; options <- parseSchemaOptions(mf)
                ) yield (input,options)
            opts.map(pair => Some(pair)).get
        }
        case "#no_schema" => 
          None
        case _ => 
          throw new Exception("Unknown value for key schema: " + value)
      }
    }
  }
  
  def parseSchemaVersion(mf: MultipartFormData[TemporaryFile], key: String): Try[String] = {
    for {
      schema_version <- parseKey(mf,"schema_version")
    ; schemaVersion <- Schemas.lookupSchema(schema_version)
    } yield schemaVersion.name
  }

  def parseSchemaInput(mf: MultipartFormData[TemporaryFile]): Try[SchemaInput] = {
    for ( input_type_schema <- parseInputType(mf,"input-schema")
        ; schema_uri <- parseKey(mf,"schema_uri")
        ; schema_file <- parseFile(mf,"schema_file")
        ; schema_textarea <- parseKey(mf,"schema_textarea")
        ; schema_format <- parseKey(mf,"schema_format")
        ; schema_name <- parseSchemaVersion(mf,"schema_name")
        )
   yield
     SchemaInput(input_type_schema,
         schema_uri, 
         schema_file, 
         schema_textarea, 
         schema_format, 
         schema_name)
  }

  def parseSchemaOptions(mf: MultipartFormData[TemporaryFile]): Try[SchemaOptions] = {
    for ( cut <- parseInt(mf,"cut",0,100)
        ; opt_iri <- parseOptIRI(mf)
        ; showSchema <- parseBoolean(mf,"showSchema")
        )
   yield
     SchemaOptions(cut,opt_iri,showSchema)
  }

  def parseBoolean(mf: MultipartFormData[TemporaryFile], key: String): Try[Boolean] = {
    for (value <- parseKey(mf,key)) yield {
      value match {
        case "true" => true
        case "false" => false
        case _ => throw new Exception("parseBoolean: unknown value " + value + " for key " + key)
      }
    }
  }

  def parseInt(mf: MultipartFormData[TemporaryFile], key: String, min: Int, max:Int): Try[Int] = {
    for (value <- parseKey(mf,key)) yield {
      val n = value.toInt
      if (n < min || n > max) throw new Exception("parseInt, n " + n + " must be between " + min + " and " + max)
      else n
    }
  }

  def parseInputType(mf: MultipartFormData[TemporaryFile], key: String): Try[InputType] = {
    for (value <- parseKey(mf,key))
      yield {
      // The following code is to transform #XXX_* to XXX 
      val pattern = ("#(.*)_.*" ).r
      val extracted = pattern.findFirstMatchIn(value).map(_ group 1)
      extracted match {
        case Some("byUri") => ByUri
        case Some("byFile") => ByFile
        case Some("byInput") => ByInput
        case Some("byEndpoint")  => ByEndpoint
        case Some("byDereference") => ByDereference
        case x => throw new Exception("Unknown value for " + key + ": " + value + ". match = " + x)
    }
   }
 } 
  
 def parseKey(mf: MultipartFormData[TemporaryFile],key:String): Try[String] = {
   if (mf.asFormUrlEncoded(key).size == 1) {
      TrySuccess(mf.asFormUrlEncoded(key).head)
   } else TryFailure(throw new 
        Exception("parseKey: key " + key + 
        " must have one value but it has = " + mf.asFormUrlEncoded(key)))
 }

 def parseKeyOrElse(mf: MultipartFormData[TemporaryFile],key:String,alternative: String): Try[String] = {
   if (mf.asFormUrlEncoded(key).size == 1) {
     TrySuccess(mf.asFormUrlEncoded(key).head)
   } else {
     Logger.info("parseKeyOrElse: key " + key + " not found")
     TrySuccess(alternative)
   } 
     
 }

}