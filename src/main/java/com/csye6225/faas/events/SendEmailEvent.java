/**
 * 
 */
package com.csye6225.faas.events;

import java.time.Instant;
import java.util.Iterator;

import org.springframework.beans.factory.annotation.Value;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableCollection;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;

/**
 * @author Snehal Patel
 *
 */
public class SendEmailEvent {

    @Value("${fromEmailAddress}")
    private static String fromEmailAddress;

    private DynamoDB amazonDynamoDB;
    private final String tableName = "csye6225";
    static String EMAIL_FROM = System.getenv("fromEmailAddress");
    static final String EMAIL_SUBJECT = "Reset Password Link";
    private static final String EMAIL_BODY = "Below is the link for password reset :- ";

    public SendEmailEvent() {
        AmazonDynamoDBClient client = new AmazonDynamoDBClient();
        client.setRegion(Region.getRegion(Regions.US_EAST_1));
        this.amazonDynamoDB = new DynamoDB(client);
    }

    public Object handleRequest(SNSEvent request, Context context) {
        
        long timeToLive = Instant.now().getEpochSecond() + 15 * 60;

        LambdaLogger logger = context.getLogger();

        if (request.getRecords() == null) {
            logger.log("There are no records available");
            return null;
        }

        if (amazonDynamoDB == null) {
            context.getLogger().log("Dynamo db object is null");
        }

        // Get Tables from DynamoDB
        TableCollection<ListTablesResult> tables = amazonDynamoDB.listTables();
        Iterator<Table> iterator = tables.iterator();
        while (iterator.hasNext()) {
            Table table = iterator.next();
            logger.log("Dynamodb table name:- " + table.getTableName());
        }

        Table table = amazonDynamoDB.getTable(tableName);
        if (table == null)
            logger.log("Table not present in dynamoDB");

        String requestsFromSQS = request.getRecords().get(0).getSNS().getMessage();
        String emailTo = requestsFromSQS.split(",")[0];
        logger.log("***************Email To: " + emailTo + " ***************");
        String token = requestsFromSQS.split(",")[1];
        logger.log("***************Token: " + token + " ***************");
        String word = "token=";
        int tokenUUIDPosition = token.lastIndexOf(word);
        int startIndex = tokenUUIDPosition + 6;
        int endIndex = startIndex + 36;
        String tokenUUID = token.substring(startIndex, endIndex);
        logger.log("***************Token UUID: " + tokenUUID + " ***************");

        Item item = amazonDynamoDB.getTable(tableName).getItem("id", emailTo);
        if ((item != null && Long.parseLong(item.get("TTL").toString()) < Instant.now().getEpochSecond())
                || item == null) {
            amazonDynamoDB.getTable(tableName).putItem(new PutItemSpec().withItem(
                    new Item().withPrimaryKey("id", emailTo).withString("token", tokenUUID).withLong("TTL", timeToLive)));

            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(EMAIL_BODY + "\n");
            String[] forgotPasswordLinks = requestsFromSQS.split(",");

            for (int index = 1; index < forgotPasswordLinks.length; index++) {
                stringBuilder.append("\n");
                stringBuilder.append(forgotPasswordLinks[index]);
            }

            Content content = new Content().withData(stringBuilder.toString());
            Body body = new Body().withText(content);
            try {
                if (EMAIL_FROM == null) {
                    EMAIL_FROM = "donotreply@prod.snehalpatel.me";
                }
                AmazonSimpleEmailService client = AmazonSimpleEmailServiceClientBuilder.standard()
                        .withRegion(Regions.US_EAST_1).build();
                SendEmailRequest emailRequest = new SendEmailRequest()
                        .withDestination(new Destination().withToAddresses(emailTo))
                        .withMessage(new Message().withBody(body)
                                .withSubject(new Content().withCharset("UTF-8").withData(EMAIL_SUBJECT)))
                        .withSource(EMAIL_FROM);
                client.sendEmail(emailRequest);
                logger.log("***************Email Request: " + emailRequest.toString() + " ***************");
                logger.log("Email sent to recipient successfully!");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return null;
    }
}
