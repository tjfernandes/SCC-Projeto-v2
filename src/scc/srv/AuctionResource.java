package scc.srv;


import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.glassfish.jaxb.runtime.v2.schemagen.xmlschema.Redefinable;
import org.jboss.resteasy.annotations.cache.Cache;
import redis.clients.jedis.Jedis;
import scc.cache.RedisCache;
import scc.data.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Path("/auction")
public class AuctionResource {

    private static final String AUCTION_ALREADY_EXISTS_EXCEPTION = "Auction already exists";
    private static final String AUCTION_DOES_NOT_EXIST_EXCEPTION = "Auction does not exist";
    private static final String AUCTION_IS_NOT_OPEN = "Auction is either closed or deleted";
    private static final String INVALID_BID_TIME = "Bid time is greater than auction end time";
    private static final String BID_DOES_NOT_EXIST_EXCEPTION = "Bid does not exist";
    private static final String QUESTION_DOES_NOT_EXIST_EXCEPTION = "Question does not exist";
    private static final String BID_VALUE_IS_TO_LOW_EXCEPTION = "There is a value with greater value";
    private static final String INVALID_AUCTION_EXCEPTION = "Invalid Auction";
    private static final String INVALID_BID_EXCEPTION = "Invalid Bid";
    private static final String INVALID_QUESTION_EXCEPTION = "Invalid Question";
    private static final String AUCTION_ID_DOES_NOT_MATCH_EXCEPTION = "Auction id on the path does not match the one on the %s object";

    private CosmosDBLayer db;

    private ObjectMapper mapper;

    public AuctionResource() {
        db = CosmosDBLayer.getInstance();
        mapper = new ObjectMapper();
    }

    @POST
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Auction createAuction(@CookieParam(UsersResource.COOKIE_PARAM_SESSION) Cookie session, Auction auction){
        isAuctionValid(auction);

        Auction auctionResponse =null;

        UsersResource.checkCookieUser(session,auction.getOwnerId());

        try{
            Jedis jedis = null;

            if(RedisCache.USE_CACHE) jedis = RedisCache.getCachePool().getResource();

            if(RedisCache.USE_CACHE){
                String cacheRes = jedis.get(RedisCache.CACHE_AUCTION_PREFIX+auction.getId());
                if(cacheRes !=null) throw new WebApplicationException(AUCTION_ALREADY_EXISTS_EXCEPTION);
            }


            var auctionExistsResponse = db.getAuctionById(auction.getId());
            if(auctionExistsResponse.iterator().hasNext()) throw new WebApplicationException(AUCTION_ALREADY_EXISTS_EXCEPTION);

            getUserById(auction.getOwnerId());

            auctionResponse= db.putAuction(auction).getItem();
            if(RedisCache.USE_CACHE) jedis.setex(RedisCache.CACHE_AUCTION_PREFIX+auction.getId(),RedisCache.DEFAULT_CACHE_TIMEOUT, mapper.writeValueAsString(auctionResponse));
        }catch (Exception e) {
            e.printStackTrace();
        }
        return auctionResponse;
    }

    @GET
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Auction getAuction(@PathParam("id") String auctionId){
        return getAuctionById(auctionId);
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Auction updateAuction(@CookieParam(UsersResource.COOKIE_PARAM_SESSION) Cookie session, @PathParam("id") String auctionId,Auction auction){
        Auction auctionResponse = getAuctionById(auctionId);

        UsersResource.checkCookieUser(session,auctionResponse.getOwnerId());

        return this.updateAuction(auctionId, auction, auctionResponse);
    }

    private Auction updateAuction(String auctionId, Auction newAuction, Auction oldAuction) {
        CosmosItemResponse<Auction> response = null;
        try {
            Jedis jedis = null;
            if(RedisCache.USE_CACHE) jedis = RedisCache.getCachePool().getResource();

            if(newAuction.getDescription() != null) oldAuction.setDescription(newAuction.getDescription());
            if(newAuction.getImageId() != null) oldAuction.setImageId(newAuction.getImageId());
            if(newAuction.getTitle() != null) oldAuction.setTitle(newAuction.getTitle());
            if(newAuction.getAuctionStatus() != null && oldAuction.getAuctionStatus() == AuctionStatus.OPEN) oldAuction.setAuctionStatus(newAuction.getAuctionStatus());

            response = db.updateAuction(auctionId,oldAuction);

            if(RedisCache.USE_CACHE) jedis.setex(RedisCache.CACHE_AUCTION_PREFIX+auctionId,RedisCache.DEFAULT_CACHE_TIMEOUT,mapper.writeValueAsString(response.getItem()));
        }catch (Exception e){
            e.printStackTrace();
        }

        return response.getItem();
    }


    @POST
    @Path("/{id}/bid")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Bid createBid(@PathParam("id") String auctionId, @CookieParam(UsersResource.COOKIE_PARAM_SESSION) Cookie session, Bid bid){

        isBidValid(bid);

        UsersResource.checkCookieUser(session, bid.getUserId());

        if(!bid.getAuctionId().equals(auctionId)) throw new WebApplicationException(String.format(AUCTION_ID_DOES_NOT_MATCH_EXCEPTION,"bid"));


        Auction auction = getAuctionById(auctionId);
        getUserById(bid.getUserId());

        if(auction.getAuctionStatus() != AuctionStatus.OPEN) throw new WebApplicationException(AUCTION_IS_NOT_OPEN);

        if (bid.getTime().after(auction.getEndDate()) ) throw new WebApplicationException(INVALID_BID_TIME);

        if(bid.getAmount() < auction.getMinimumPrice()) throw  new WebApplicationException(BID_VALUE_IS_TO_LOW_EXCEPTION);

        if(auction.getWinnerBid() != null && bid.getAmount() <= auction.getWinnerBid().getAmount()) {
            throw new WebApplicationException(BID_VALUE_IS_TO_LOW_EXCEPTION);
        }

        Bid createdBid = db.putBid(bid).getItem();

        auction.setWinnerBid(createdBid);

        Auction newAuction = this.updateAuction(auctionId, auction, auction);

        if(RedisCache.USE_CACHE){
            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.setex(RedisCache.CACHE_AUCTION_PREFIX+auctionId, RedisCache.DEFAULT_CACHE_TIMEOUT, mapper.writeValueAsString(newAuction));
                jedis.setex(RedisCache.CACHE_BID_PREFIX+createdBid.getId(),RedisCache.DEFAULT_CACHE_TIMEOUT,mapper.writeValueAsString(createdBid));
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        return createdBid;
    }

    @GET
    @Path("/{id}/bid")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Bid> listBids(@PathParam("id") String auctionId){
        var getAuctionResponse = db.getAuctionById(auctionId);
        if(!getAuctionResponse.iterator().hasNext()) throw new WebApplicationException(AUCTION_DOES_NOT_EXIST_EXCEPTION);
        List<Bid> bids = new ArrayList<>();
        CosmosPagedIterable<BidDAO> result = db.listAuctionBids(auctionId);
        Iterator<BidDAO> ite = result.iterator();
        while (ite.hasNext()){
            bids.add(ite.next().toBid());
        }
        return bids;
    }

    @POST
    @Path("/{id}/question")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Question createQuestion(@PathParam("id") String auctionId, @CookieParam(UsersResource.COOKIE_PARAM_SESSION) Cookie session, Question question){
        isQuestionValid(question);

        UsersResource.checkCookieUser(session,question.getUserId());

        if(!question.getAuctionId().equals(auctionId)) throw new WebApplicationException(String.format(AUCTION_ID_DOES_NOT_MATCH_EXCEPTION,"question"));

        getAuctionById(auctionId);

        Question newQuestion = db.putQuestion(question).getItem();
        if(RedisCache.USE_CACHE) {
            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.setex(RedisCache.CACHE_QUESTION_PREFIX + newQuestion.getId(), RedisCache.DEFAULT_CACHE_TIMEOUT, mapper.writeValueAsString(newQuestion));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return newQuestion;
    }

    @POST
    @Path("/{id}/question/{questionId}/reply")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Question ReplyToQuestion(@CookieParam(UsersResource.COOKIE_PARAM_SESSION) Cookie session,
                                    @PathParam("id") String auctionId,
                                    @PathParam("questionId") String questionId,
                                    String reply){

        Auction auction = getAuctionById(auctionId);

        UsersResource.checkCookieUser(session,auction.getOwnerId());

        Question modifiedQuestion = db.replyQuestion(questionId,reply).getItem();

        if(RedisCache.USE_CACHE) {
            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.setex(RedisCache.CACHE_QUESTION_PREFIX + modifiedQuestion.getId(), RedisCache.DEFAULT_CACHE_TIMEOUT, mapper.writeValueAsString(modifiedQuestion));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return modifiedQuestion;
    }

    @GET
    @Path("/any/popular")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> listPopularAuctions() {
        List<Auction> auctions = new ArrayList<>();
        CosmosPagedIterable<AuctionDAO> response = db.listPopularAuctions();
        Iterator<AuctionDAO> ite = response.iterator();
        while (ite.hasNext()){
            auctions.add(ite.next().toAuction());
        }
        return auctions;
    }

    @GET
    @Path("/{id}/question")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Question> listQuestions(@PathParam("id") String auctionId){

        getAuctionById(auctionId);

        List<Question> questions = new ArrayList<>();
        CosmosPagedIterable<QuestionDAO> response = db.listQuestions(auctionId);
        Iterator<QuestionDAO> ite = response.iterator();
        while (ite.hasNext()){
            questions.add(ite.next().toQuestion());
        }
        return questions;
    }

    @GET
    @Path("/{id}/question/{questionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Question getQuestion(@PathParam("id") String auctionId,@PathParam("questionId") String questionId){

        getAuctionById(auctionId);

        Question question = null;

        CosmosPagedIterable<QuestionDAO> response = db.getQuestionById(questionId);

        Iterator<QuestionDAO> ite = response.iterator();

        if(!ite.hasNext()) throw new WebApplicationException(QUESTION_DOES_NOT_EXIST_EXCEPTION);

        question = ite.next().toQuestion();

        if(RedisCache.USE_CACHE) {
            try (Jedis jedis = RedisCache.getCachePool().getResource()) {
                jedis.setex(RedisCache.CACHE_QUESTION_PREFIX + question.getId(), RedisCache.DEFAULT_CACHE_TIMEOUT, mapper.writeValueAsString(question));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return question;
    }

    @GET
    @Path("/closing")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Auction> closingAuctions(){
        List<Auction> auctions = new ArrayList<>();
        CosmosPagedIterable<AuctionDAO> response = db.getClosingAuctions();
        Iterator<AuctionDAO> ite = response.iterator();
        while (ite.hasNext()){
            auctions.add(ite.next().toAuction());
        }
        return auctions;
    }

    private Auction getAuctionById(String id){
        Iterator<AuctionDAO> response = null;
        Auction auction = null;

        try{
            Jedis jedis = null;
            if(RedisCache.USE_CACHE) {
                jedis = RedisCache.getCachePool().getResource();
                String cacheRes = jedis.get(RedisCache.CACHE_AUCTION_PREFIX + id);
                if (cacheRes != null) {
                    return mapper.readValue(cacheRes, Auction.class);
                }
            }

            response = db.getAuctionById(id).iterator();
            if(!response.hasNext()) throw new WebApplicationException(AUCTION_DOES_NOT_EXIST_EXCEPTION);
            auction = response.next().toAuction();
            if(RedisCache.USE_CACHE) jedis.setex(RedisCache.CACHE_AUCTION_PREFIX+id,RedisCache.DEFAULT_CACHE_TIMEOUT,mapper.writeValueAsString(auction));

        }catch (Exception e) {
            e.printStackTrace();
        }

        return auction;
    }

    private void isAuctionValid(Auction auction){
        if(auction.getEndDate() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getId() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getImageId() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getAuctionStatus() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getDescription() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getTitle() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getMinimumPrice() < 0 ) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
        if(auction.getOwnerId() == null) throw new WebApplicationException(INVALID_AUCTION_EXCEPTION);
    }

    private void isBidValid(Bid bid){
        if(bid.getAmount() < 0) throw new WebApplicationException(INVALID_BID_EXCEPTION);
        if(bid.getTime() == null) throw new WebApplicationException(INVALID_BID_EXCEPTION);
        if(bid.getId() == null) throw new WebApplicationException(INVALID_BID_EXCEPTION);
        if(bid.getAuctionId() == null) throw new WebApplicationException(INVALID_BID_EXCEPTION);
        if(bid.getUserId() == null) throw new WebApplicationException(INVALID_BID_EXCEPTION);
    }

    private void isQuestionValid(Question question){
        if(question.getId() == null) throw  new WebApplicationException(INVALID_QUESTION_EXCEPTION);
        if(question.getAuctionId() == null) throw  new WebApplicationException(INVALID_QUESTION_EXCEPTION);
        if(question.getUserId() == null) throw  new WebApplicationException(INVALID_QUESTION_EXCEPTION);
        if(question.getMessage() == null) throw  new WebApplicationException(INVALID_QUESTION_EXCEPTION);
    }

    private User getUserById(String id){
        Iterator<UserDAO> response = null;
        User user = null;

        try{
            Jedis jedis = null;
            if(RedisCache.USE_CACHE){
                jedis = RedisCache.getCachePool().getResource();
                String cacheRes = jedis.get(RedisCache.CACHE_USER_PREFIX+id);
                if(cacheRes !=null) {
                    return mapper.readValue(cacheRes,User.class);
                }
            }
            response = db.getUserById(id).iterator();
            if(!response.hasNext()) throw new WebApplicationException(UsersResource.USER_DOES_NOT_EXIST_EXCEPTION);
            user = response.next().toUser();
            if(RedisCache.USE_CACHE) jedis.setex(RedisCache.CACHE_USER_PREFIX+id,RedisCache.DEFAULT_CACHE_TIMEOUT,mapper.writeValueAsString(user));

        }catch (Exception e) {
            e.printStackTrace();
        }

        return user;
    }
}