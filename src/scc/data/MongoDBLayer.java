package scc.data;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemResponse;
import com.mongodb.*;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import jakarta.ws.rs.WebApplicationException;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;

import java.time.LocalDateTime;
import java.util.Date;

import static com.mongodb.client.model.Filters.*;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class MongoDBLayer {

    private static final String DB_HOSTNAME = System.getenv("ME_CONFIG_MONGODB_SERVER");
    private static final String DB_NAME = "scc23dbrosatiago";
    private static final int DB_PORT = 27017;

    private static CodecRegistry pojoCodecRegistry;

    private static MongoDBLayer instance;

    private int auctionsCounter;
    private int bidsCounter;
    private int questionsCounter;

    public static synchronized MongoDBLayer getInstance() {
        if( instance != null) {
            return instance;
        }

        System.out.println("MONGO HOSTNAME: " + DB_HOSTNAME);

        pojoCodecRegistry = fromRegistries(MongoClient.getDefaultCodecRegistry(),
                fromProviders(PojoCodecProvider.builder().automatic(true).build()));


        //é preciso o url -> hostname::port
        MongoClient client = new MongoClient(DB_HOSTNAME+":"+DB_PORT, MongoClientOptions.builder().codecRegistry(pojoCodecRegistry).build());

        //MongoClient client = new MongoClient(DB_HOSTNAME,DB_PORT);

        instance = new MongoDBLayer(client);
        return instance;
    }

    private MongoClient client;
    private MongoDatabase db;

    private MongoCollection<User> users;
    private MongoCollection<Auction> auctions;
    private MongoCollection<Bid> bids;
    private MongoCollection<Question> questions;


    public MongoDBLayer(MongoClient client) {
        this.auctionsCounter = 1;
        this.bidsCounter = 1;
        this.questionsCounter = 1;
        this.client = client;
    }

    private synchronized void init() {
        if( db != null)
            return;
        db = client.getDatabase(DB_NAME);
        users = db.getCollection("users", User.class);
        auctions = db.getCollection("auctions",  Auction.class);
        bids = db.getCollection("bids",Bid.class);
        questions = db.getCollection("questions", Question.class);
    }

    private void checkError(int error){
        switch ( error ){
            case 11000:

            case 12582:

            case 11001:
                throw new WebApplicationException("DUPLICATE_KEY");
            case 50:
                throw new WebApplicationException("EXECUTION_TIMEOUT");
            default:
                throw new WebApplicationException("UNCATEGORIZED");
        }
    }

    public int getId(int collectionType){
        switch (collectionType){
            case 0:
                return auctionsCounter++;

            case 1:
                return bidsCounter++;

            case 2:
                return questionsCounter++;

            default:
                throw new WebApplicationException("INVALID COLLECTION TYPE");
        }
    }

    public User putUser(User user){
        init();
        try{
            users.insertOne(user);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return user;
    }

    public User getUser(String userId){
        init();
        User user = null;
        try{
            Bson query = eq("_id", userId);
            user = users.find(query).first();
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return user;
    }

    public User updateUser(String id, User user){
        init();
        try{
            Bson query = eq("_id",id);
            users.replaceOne(query,user);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return user;
    }

    public User deleteUser(String id,User user){
        init();
        try{
            Bson query = eq("_id",id);
            users.deleteOne(query);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return user;
    }

    public FindIterable<Auction> userAuctions(String userId,String auctionStatus){
        init();
        FindIterable<Auction> list = null;
        try{
            Bson query = and(eq("userId",userId),eq("auctionStatus",auctionStatus));
            list = auctions.find(query);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return list;
    }

    //TODO ISTO TA ESTRANHO
    public DistinctIterable<Bid> userFollowedAuctions(String userId){
        init();
        DistinctIterable<Bid> list = null;
        try{
            Bson query = eq("userId",userId);
            list = bids.distinct("auctionId", query,Bid.class);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return list;
    }

    public Auction putAuction(Auction auction){
        init();
        try{
            auctions.insertOne(auction);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return auction;
    }

    public Auction getAuction(String auctionId){
        init();
        Auction auction = null;
        try{
            Bson query = eq("_id",auctionId);
            auction = auctions.find(query).first();
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return auction;
    }

    public Auction updateAuction(String id, Auction auction){
        init();
        try{
            Bson query = eq("_id",id);
            auctions.replaceOne(query,auction);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return auction;
    }

    public Bid putBid(Bid bid){
        init();
        try{
            bids.insertOne(bid);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return bid;
    }

    public FindIterable<Bid> listAuctionsBids(String auctionId){
        init();
        FindIterable<Bid> list = null;
        try{
            Bson query = eq("auctionId",auctionId);
            list = bids.find(query);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return list;
    }

    public Question putQuestion(Question question){
        init();
        try{
            questions.insertOne(question);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return question;
    }

    public Question getQuestion(String questionId){
        init();
        Question question = null;
        try{
            Bson query = eq("_id",questionId);
            question = questions.find(query).first();
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return question;
    }

    public Question replyToQuestion(String questionId,String reply){
        init();
        Question question = null;
        try{
            Bson query = eq("_id",questionId);
            question = questions.find(query).first();
            question.setReply(reply);
            questions.replaceOne(query,question);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return question;
    }

    public FindIterable<Question> listAuctionsQuestions(String auctionId){
        init();
        FindIterable<Question> list = null;
        try{
            Bson query = eq("auctionId",auctionId);
            list = questions.find(query);
        }catch (MongoException e) {
            checkError(e.getCode());
        }
        return list;
    }

    //todo essa verificacao n sei se ta certa
    public FindIterable<Auction> listClosingAuctions(){
        init();
        FindIterable<Auction> list = null;

        try{
            LocalDateTime now = LocalDateTime.now();
            Bson query = and(lt("endDate", now.plusHours(1)),eq("auctionStatus","OPEN"));
            list = auctions.find(query);
        }catch (MongoException e) {
            checkError(e.getCode());
        }

        return list;
    }
}
