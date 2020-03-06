/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/

package org.pih.warehouse.inventory

import grails.converters.JSON
import org.grails.plugins.csv.CSVWriter
import org.pih.warehouse.api.StockMovement
import org.pih.warehouse.api.StockMovementItem
import org.pih.warehouse.api.StockMovementType
import org.pih.warehouse.core.ActivityCode
import org.pih.warehouse.core.Document
import org.pih.warehouse.core.DocumentCommand
import org.pih.warehouse.core.DocumentType
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User
import org.pih.warehouse.importer.ImportDataCommand
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.shipping.Shipment
import org.pih.warehouse.shipping.ShipmentStatusCode

class StockMovementController {

    def dataService
    def stockMovementService
    def requisitionService
    def shipmentService

    // This template is generated by webpack during application start
    def index = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        if (stockMovement.stockMovementType == StockMovementType.OUTBOUND) {
            redirect(action: "createOutbound", params: params)
        }
        else if (stockMovement.stockMovementType == StockMovementType.INBOUND) {
            if (stockMovement.isFromOrder) {
                redirect(action: "createPurchaseOrders", params: params)
            } else {
                redirect(action: "createInbound", params: params)
            }
        }
    }

    def create = {
        render(template: "/common/react", params: params)
    }

    def createOutbound = {
        render(template: "/common/react", params: params)
    }

    def createInbound = {
        render(template: "/common/react", params: params)
    }

    def createPurchaseOrders = {
        render(template: "/common/react", params: params)
    }

    def show = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        [stockMovement: stockMovement]
    }

    def list = {

        def max = params.max ? params.max as int : 10
        def offset = params.offset ? params.offset as int : 0
        User currentUser = User.get(session?.user?.id)
        Location currentLocation = Location.get(session?.warehouse?.id)
        boolean incoming = params?.direction == "INBOUND" || params.destination?.id == currentLocation?.id

        if (params.direction == "OUTBOUND") {
            params.origin = params.origin ?: currentLocation
            params.destination = params.destination ?: null
        } else if (params.direction == "INBOUND") {
            params.origin = params.origin ?: null
            params.destination = params.destination ?: currentLocation
        } else {
            if (params.origin?.id == currentLocation?.id && params.destination?.id == currentLocation?.id) {
                params.direction = null
            } else if (params.origin?.id == currentLocation?.id) {
                params.direction = "OUTBOUND"
            } else if (params.destination?.id == currentLocation?.id) {
                params.direction = "INBOUND"
            } else {
                params.origin = params.origin ?: currentLocation
                params.destination = params.destination ?: currentLocation
            }
        }

        if (params.format) {
            max = null
            offset = null
        }

        // Discard the requisition so it does not get saved at the end of the request
        Requisition requisition = new Requisition(params)
        requisition.discard()

        // Create stock movement to be used as search criteria
        StockMovement stockMovement = new StockMovement()
        if (params.q) {
            stockMovement.identifier = "%" + params.q + "%"
            stockMovement.name = "%" + params.q + "%"
            stockMovement.description = "%" + params.q + "%"
        }
        stockMovement.requestedBy = requisition.requestedBy
        stockMovement.createdBy = requisition.createdBy
        stockMovement.origin = requisition.origin
        stockMovement.destination = requisition.destination
        stockMovement.statusCode = requisition?.status ? requisition?.status.toString() : null
        stockMovement.receiptStatusCode = params?.receiptStatusCode ? params.receiptStatusCode as ShipmentStatusCode : null

        def stockMovements = stockMovementService.getInboundStockMovements(stockMovement, [max:max, offset:offset])
        def statistics = [:]

        if (params.format && stockMovements) {

            def sw = new StringWriter()
            def csv = new CSVWriter(sw, {
                "Status" { it.status }
                "Receipt Status" { it.receiptStatus }
                "Identifier" { it.id }
                "Name" { it.name }
                "Origin" { it.origin }
                "Destination" { it.destination }
                "Stocklist" { it.stocklist }
                "Requested by" { it.requestedBy }
                "Date Requested" { it.dateRequested }
                "Date Created" { it.dateCreated }
                "Date Shipped" { it.dateShipepd }
            })

            stockMovements.each { stockMov ->
                csv << [
                        status       : stockMov.status,
                        receiptStatus: stockMov.shipment?.status,
                        id           : stockMov.identifier,
                        name         : stockMov.description,
                        origin       : stockMov.origin?.name ?: "",
                        destination  : stockMov.destination?.name ?: "",
                        stocklist    : stockMov.stocklist?.name ?: "",
                        requestedBy  : stockMov.requestedBy ?: warehouse.message(code: 'default.none.label'),
                        dateRequested: stockMov.dateRequested.format("MM-dd-yyyy") ?: "",
                        dateCreated  : stockMov.requisition?.dateCreated?.format("MM-dd-yyyy") ?: "",
                        dateShipepd  : stockMov.shipment?.expectedShippingDate?.format("MM-dd-yyyy") ?: "",
                ]
            }

            response.setHeader("Content-disposition", "attachment; filename=\"StockMovements-${new Date().format("yyyyMMdd-hhmmss")}.csv\"")
            render(contentType: "text/csv", text: sw.toString(), encoding: "UTF-8")
        }

        if (params.submitted) {
            flash.message = "${warehouse.message(code:'request.submitMessage.label')} ${params.movementNumber}"
        }

        render(view: "list", params: params, model: [stockMovements: stockMovements, statistics: statistics, incoming: incoming])
    }

    def rollback = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        boolean isOrigin = stockMovement?.requisition?.origin?.id == session.warehouse.id
        boolean isDestination = stockMovement?.requisition?.destination?.id == session.warehouse.id
        boolean canManageInventory = stockMovement?.requisition?.origin?.supports(ActivityCode.MANAGE_INVENTORY)
        if ((canManageInventory && isOrigin) || (!canManageInventory && isDestination)) {
            try {
                stockMovementService.rollbackStockMovement(params.id)
                flash.message = "Successfully rolled back stock movement with ID ${params.id}"
            } catch (Exception e) {
                log.warn("Unable to rollback stock movement with ID ${params.id}: " + e.message)
                flash.message = "Unable to rollback stock movement with ID ${params.id}: " + e.message
            }
        } else {
            flash.error = "You are not able to rollback shipment from your location."
        }

        redirect(action: "show", id: params.id)
    }


    def removeStockMovement = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        boolean isOrigin = stockMovement?.requisition?.origin?.id == session.warehouse.id
        boolean isDestination = stockMovement?.requisition?.destination?.id == session.warehouse.id
        boolean canManageInventory = stockMovement?.requisition?.origin?.supports(ActivityCode.MANAGE_INVENTORY)
        if (!((canManageInventory && isOrigin) || (!canManageInventory && isDestination))) {
            flash.error = "You are not able to delete stock movement from your location."
            if (params.show) {
                redirect(action: "show", id: params.id)
                return
            }
        } else if (stockMovement?.shipment?.currentStatus == ShipmentStatusCode.PENDING || !stockMovement?.shipment?.currentStatus) {
            try {
                Requisition requisition = stockMovement?.requisition
                if (requisition) {
                    def shipments = stockMovement?.requisition?.shipments
                    shipments.toArray().each { Shipment shipment ->
                        requisition.removeFromShipments(shipment)
                        if (!shipment?.events?.empty) {
                            shipmentService.rollbackLastEvent(shipment)
                        }
                        shipmentService.deleteShipment(shipment)
                    }
                    //requisitionService.rollbackRequisition(requisition)
                    requisitionService.deleteRequisition(requisition)
                }
                flash.message = "Successfully deleted stock movement with ID ${params.id}"
            } catch (Exception e) {
                log.error("Unable to delete stock movement with ID ${params.id}: " + e.message, e)
                flash.message = "Unable to delete stock movement with ID ${params.id}: " + e.message
            }
        } else {
            flash.message = "You cannot delete this shipment"
        }

        redirect(action: "list")
    }

    def requisition = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        render(template: "requisition", model: [stockMovement: stockMovement])

    }

    def documents = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        render(template: "documents", model: [stockMovement: stockMovement])

    }

    def packingList = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        render(template: "packingList", model: [stockMovement: stockMovement])
    }

    def receipts = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        def receiptItems = stockMovementService.getStockMovementReceiptItems(stockMovement)
        render(template: "receipts", model: [receiptItems: receiptItems])
    }


    def uploadDocument = { DocumentCommand command ->
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)

        Shipment shipment = stockMovement.shipment
        Document document = new Document()
        document.fileContents = command.fileContents.bytes
        document.contentType = command.fileContents.fileItem.contentType
        document.name = command.fileContents.fileItem.name
        document.filename = command.fileContents.fileItem.name
        document.documentType = DocumentType.get(9)

        shipment.addToDocuments(document)
        shipment.save()

        render([data: "Document was uploaded successfully"] as JSON)
    }

    def addDocument = {
        log.info params
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)

        Shipment shipmentInstance = stockMovement.shipment
        def documentInstance = Document.get(params?.document?.id)
        if (!documentInstance) {
            documentInstance = new Document()
        }
        if (!shipmentInstance) {
            flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'shipment.label', default: 'Shipment'), params.id])}"
            redirect(action: "list")
        }
        render(view: "addDocument", model: [shipmentInstance: shipmentInstance, documentInstance: documentInstance])
    }

    def exportCsv = {
        StockMovement stockMovement = stockMovementService.getStockMovement(params.id)
        List lineItems = stockMovementService.buildStockMovementItemList(stockMovement)
        String csv = dataService.generateCsv(lineItems)
        response.setHeader("Content-disposition", "attachment; filename=\"StockMovementItems-${params.id}.csv\"")
        render(contentType: "text/csv", text: csv.toString(), encoding: "UTF-8")
    }


    def importCsv = { ImportDataCommand command ->

        try {
            StockMovement stockMovement = stockMovementService.getStockMovement(params.id)

            def importFile = command.importFile
            if (importFile.isEmpty()) {
                throw new IllegalArgumentException("File cannot be empty")
            }

            if (importFile.fileItem.contentType != "text/csv") {
                throw new IllegalArgumentException("File must be in CSV format")
            }

            String csv = new String(importFile.bytes)
            def settings = [separatorChar: ',', skipLines: 1]
            csv.toCsvReader(settings).eachLine { tokens ->

                StockMovementItem stockMovementItem = StockMovementItem.createFromTokens(tokens)
                stockMovementItem.stockMovement = stockMovement
                stockMovement.lineItems.add(stockMovementItem)
            }
            stockMovementService.updateItems(stockMovement)

        } catch (Exception e) {
            // FIXME The global error handler does not return JSON for multipart uploads
            log.warn("Error occurred while importing CSV: " + e.message, e)
            response.status = 500
            render([errorCode: 500, errorMessage: e?.message ?: "An unknown error occurred during import"] as JSON)
            return
        }

        render([data: "Data will be imported successfully"] as JSON)
    }

    def exportItems = {
        def shipmentItems = []
        def shipments = shipmentService.getShipmentsByDestination(session.warehouse)

        shipments.findAll {
            it.currentStatus == ShipmentStatusCode.SHIPPED || it.currentStatus == ShipmentStatusCode.PARTIALLY_RECEIVED
        }.each { shipment ->
            shipment.shipmentItems.findAll { it.quantityRemaining > 0 }.groupBy {
                it.product
            }.each { product, value ->
                shipmentItems << [
                        productCode         : product.productCode,
                        productName         : product.name,
                        quantity            : value.sum { it.quantityRemaining },
                        expectedShippingDate: formatDate(date: shipment.expectedShippingDate, format: "dd-MMM-yy"),
                        shipmentNumber      : shipment.shipmentNumber,
                        shipmentName        : shipment.name,
                        origin              : shipment.origin,
                        destination         : shipment.destination,
                ]
            }
        }


        if (shipmentItems) {
            def date = new Date()
            def sw = new StringWriter()

            def csv = new CSVWriter(sw, {
                "Code" { it.productCode }
                "Product Name" { it.productName }
                "Quantity Incoming" { it.quantity }
                "Expected Shipping Date" { it.expectedShippingDate }
                "Shipment Number" { it.shipmentNumber }
                "Shipment Name" { it.shipmentName }
                "Origin" { it.origin }
                "Destination" { it.destination }
            })

            shipmentItems.each { shipmentItem ->
                csv << [
                        productCode         : shipmentItem.productCode,
                        productName         : shipmentItem.productName,
                        quantity            : shipmentItem.quantity,
                        expectedShippingDate: shipmentItem.expectedShippingDate,
                        shipmentNumber      : shipmentItem.shipmentNumber,
                        shipmentName        : shipmentItem.shipmentName,
                        origin              : shipmentItem.origin,
                        destination         : shipmentItem.destination,
                ]
            }
            response.contentType = "text/csv"
            response.setHeader("Content-disposition", "attachment; filename=\"Items shipped not received_${session.warehouse.name}_${date.format("yyyyMMdd-hhmmss")}.csv\"")
            render(contentType: "text/csv", text: csv.writer.toString())
            return
        } else {
            render(text: 'No shipments found', status: 404)
        }
    }

}
