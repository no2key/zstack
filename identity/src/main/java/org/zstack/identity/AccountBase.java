package org.zstack.identity;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.Platform;
import org.zstack.core.cascade.CascadeConstant;
import org.zstack.core.cascade.CascadeFacade;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.core.Completion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.identity.*;
import org.zstack.header.message.APIDeleteMessage.DeletionMode;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.utils.gson.JSONObjectUtil;

import javax.persistence.Query;
import javax.persistence.Tuple;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.zstack.utils.CollectionDSL.list;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class AccountBase extends AbstractAccount {
    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private CascadeFacade casf;

    private AccountVO vo;

    public AccountBase(AccountVO vo) {
        this.vo = vo;
    }

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    private void handle(APIResetAccountPasswordMsg msg) {
        AccountVO account = dbf.findByUuid(msg.getUuid(), AccountVO.class);
        account.setPassword(msg.getPassword());
        account = dbf.updateAndRefresh(account);

        APIResetAccountPasswordEvent evt = new APIResetAccountPasswordEvent(msg.getId());
        evt.setInventory(AccountInventory.valueOf(account));
        bus.publish(evt);
    }

    private void handleLocalMessage(Message msg) {
        if (msg instanceof AccountDeletionMsg) {
            handle((AccountDeletionMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }


    private void handle(final APIDeleteAccountMsg msg) {
        final APIDeleteAccountEvent evt = new APIDeleteAccountEvent(msg.getId());

        final AccountVO vo = dbf.findByUuid(msg.getUuid(), AccountVO.class);
        if (vo == null) {
            bus.publish(evt);
            return;
        }

        final String issuer = AccountVO.class.getSimpleName();
        final List<AccountInventory> ctx = list(AccountInventory.valueOf(vo));
        final FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("delete-account-%s", vo.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                if (msg.getDeletionMode() == DeletionMode.Permissive) {
                    flow(new NoRollbackFlow() {
                        String __name__ = "check-before-deleting";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            casf.asyncCascade(CascadeConstant.DELETION_CHECK_CODE, issuer, ctx, new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "delete";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            casf.asyncCascade(CascadeConstant.DELETION_DELETE_CODE, issuer, ctx, new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });
                } else {
                    flow(new NoRollbackFlow() {
                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            casf.asyncCascade(CascadeConstant.DELETION_FORCE_DELETE_CODE, issuer, ctx, new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });
                }

                done(new FlowDoneHandler(msg) {
                    @Override
                    public void handle(Map data) {
                        dbf.remove(vo);
                        bus.publish(evt);
                    }
                });

                error(new FlowErrorHandler(msg) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        evt.setErrorCode(errCode);
                        bus.publish(evt);
                    }
                });
            }
        }).start();
    }

    @Transactional
    private void deleteRelatedResources() {
        String sql = "delete from QuotaVO q where q.identityUuid = :uuid and q.identityType = :itype";
        Query q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuid", vo.getUuid());
        q.setParameter("itype", AccountVO.class.getSimpleName());
        q.executeUpdate();

        sql = "delete from UserVO u where u.accountUuid = :uuid";
        q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuid", vo.getUuid());
        q.executeUpdate();

        sql = "delete from UserGroupVO g where g.accountUuid = :uuid";
        q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuid", vo.getUuid());
        q.executeUpdate();

        sql = "delete from PolicyVO p where p.accountUuid = :uuid";
        q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuid", vo.getUuid());
        q.executeUpdate();

        sql = "delete from SharedResourceVO s where s.ownerAccountUuid = :uuid or s.receiverAccountUuid = :uuid";
        q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuid", vo.getUuid());
        q.executeUpdate();
    }

    private void handle(AccountDeletionMsg msg) {
        AccountDeletionReply reply = new AccountDeletionReply();
        deleteRelatedResources();
        bus.reply(msg, reply);
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APIResetAccountPasswordMsg) {
            handle((APIResetAccountPasswordMsg) msg);
        } else if (msg instanceof APICreateUserMsg) {
            handle((APICreateUserMsg) msg);
        } else if (msg instanceof APICreatePolicyMsg) {
            handle((APICreatePolicyMsg) msg);
        } else if (msg instanceof APIAttachPolicyToUserMsg) {
            handle((APIAttachPolicyToUserMsg)msg);
        } else if (msg instanceof APICreateUserGroupMsg) {
            handle((APICreateUserGroupMsg)msg);
        } else if (msg instanceof APIAttachPolicyToUserGroupMsg) {
            handle((APIAttachPolicyToUserGroupMsg)msg);
        } else if (msg instanceof APIAddUserToGroupMsg) {
            handle((APIAddUserToGroupMsg) msg);
        } else if (msg instanceof APIDeleteUserGroupMsg) {
            handle((APIDeleteUserGroupMsg) msg);
        } else if (msg instanceof APIDeleteUserMsg) {
            handle((APIDeleteUserMsg) msg);
        } else if (msg instanceof APIDeletePolicyMsg) {
            handle((APIDeletePolicyMsg) msg);
        } else if (msg instanceof APIDetachPolicyFromUserMsg) {
            handle((APIDetachPolicyFromUserMsg) msg);
        } else if (msg instanceof APIDetachPolicyFromUserGroupMsg) {
            handle((APIDetachPolicyFromUserGroupMsg) msg);
        } else if (msg instanceof APIRemoveUserFromGroupMsg) {
            handle((APIRemoveUserFromGroupMsg) msg);
        } else if (msg instanceof APIResetUserPasswordMsg) {
            handle((APIResetUserPasswordMsg) msg);
        } else if (msg instanceof APIShareResourceMsg) {
            handle((APIShareResourceMsg) msg);
        } else if (msg instanceof APIRevokeResourceSharingMsg) {
            handle((APIRevokeResourceSharingMsg) msg);
        } else if (msg instanceof APIUpdateQuotaMsg) {
            handle((APIUpdateQuotaMsg) msg);
        } else if (msg instanceof APIDeleteAccountMsg) {
            handle((APIDeleteAccountMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(APIUpdateQuotaMsg msg) {
        SimpleQuery<QuotaVO> q = dbf.createQuery(QuotaVO.class);
        q.add(QuotaVO_.identityUuid, Op.EQ, msg.getIdentityUuid());
        q.add(QuotaVO_.name, Op.EQ, msg.getName());
        QuotaVO quota = q.find();

        if (quota == null) {
            throw new OperationFailureException(errf.stringToInvalidArgumentError(
                    String.format("cannot find Quota[name: %s] for the account[uuid: %s]", msg.getName(), msg.getIdentityUuid())
            ));
        }

        quota.setValue(msg.getValue());
        quota = dbf.updateAndRefresh(quota);

        APIUpdateQuotaEvent evt = new APIUpdateQuotaEvent(msg.getId());
        evt.setInventory(QuotaInventory.valueOf(quota));
        bus.publish(evt);
    }

    @Transactional
    private void handle(APIRevokeResourceSharingMsg msg) {
        Query q = null;
        if (msg.isAll()) {
            String sql = "delete from SharedResourceVO vo where vo.ownerAccountUuid = :auuid and vo.resourceUuid in (:resUuids)";
            q = dbf.getEntityManager().createQuery(sql);
            q.setParameter("auuid", vo.getUuid());
            q.setParameter("resUuids", msg.getResourceUuids());
        }

        if (msg.isToPublic()) {
            String sql = "delete from SharedResourceVO vo where vo.toPublic = :public and vo.ownerAccountUuid = :auuid and vo.resourceUuid in (:resUuids)";
            q = dbf.getEntityManager().createQuery(sql);
            q.setParameter("public", msg.isToPublic());
            q.setParameter("auuid", vo.getUuid());
            q.setParameter("resUuids", msg.getResourceUuids());
        }

        if (msg.getAccountUuids() != null && !msg.getAccountUuids().isEmpty()) {
            String sql = "delete from SharedResourceVO vo where vo.receiverAccountUuid in (:ruuids) and vo.ownerAccountUuid = :auuid and vo.resourceUuid in (:resUuids)";
            q = dbf.getEntityManager().createQuery(sql);
            q.setParameter("auuid", vo.getUuid());
            q.setParameter("ruuids", msg.getAccountUuids());
            q.setParameter("resUuids", msg.getResourceUuids());
        }

        if (q != null) {
            q.executeUpdate();
        }

        APIRevokeResourceSharingEvent evt = new APIRevokeResourceSharingEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIShareResourceMsg msg) {
        SimpleQuery<AccountResourceRefVO> q = dbf.createQuery(AccountResourceRefVO.class);
        q.select(AccountResourceRefVO_.resourceUuid, AccountResourceRefVO_.resourceType);
        q.add(AccountResourceRefVO_.resourceUuid, Op.IN, msg.getResourceUuids());
        List<Tuple> ts = q.listTuple();
        Map<String, String> uuidType = new HashMap<String, String>();
        for (Tuple t : ts) {
            String resUuid = t.get(0, String.class);
            String resType = t.get(1, String.class);
            uuidType.put(resUuid, resType);
        }

        for (String ruuid : msg.getResourceUuids()) {
            if (!uuidType.containsKey(ruuid)) {
                throw new OperationFailureException(errf.stringToInvalidArgumentError(
                        String.format("the account[uuid: %s] doesn't have a resource[uuid: %s]", vo.getUuid(), ruuid)
                ));
            }
        }

        List<SharedResourceVO> vos = new ArrayList<SharedResourceVO>();
        if (msg.isToPublic()) {
            for (String ruuid : msg.getResourceUuids()) {
                SharedResourceVO svo = new SharedResourceVO();
                svo.setOwnerAccountUuid(msg.getAccountUuid());
                svo.setResourceType(uuidType.get(ruuid));
                svo.setResourceUuid(ruuid);
                svo.setToPublic(true);
                vos.add(svo);
            }
        } else {
            for (String ruuid : msg.getResourceUuids()) {
                for (String auuid : msg.getAccountUuids()) {
                    SharedResourceVO svo = new SharedResourceVO();
                    svo.setOwnerAccountUuid(msg.getAccountUuid());
                    svo.setResourceType(uuidType.get(ruuid));
                    svo.setResourceUuid(ruuid);
                    svo.setReceiverAccountUuid(auuid);
                    vos.add(svo);
                }
            }
        }

        dbf.persistCollection(vos);

        APIShareResourceEvent evt = new APIShareResourceEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIResetUserPasswordMsg msg) {
        UserVO user = dbf.findByUuid(msg.getUuid(), UserVO.class);
        user.setPassword(msg.getPassword());
        dbf.update(user);

        APIResetUserPasswordEvent evt = new APIResetUserPasswordEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIRemoveUserFromGroupMsg msg) {
        SimpleQuery<UserGroupUserRefVO> q = dbf.createQuery(UserGroupUserRefVO.class);
        q.add(UserGroupUserRefVO_.groupUuid, Op.EQ, msg.getGroupUuid());
        q.add(UserGroupUserRefVO_.userUuid, Op.EQ, msg.getUserUuid());
        UserGroupUserRefVO ref = q.find();
        if (ref != null) {
            dbf.remove(ref);
        }

        bus.publish(new APIRemoveUserFromGroupEvent(msg.getId()));
    }

    private void handle(APIDetachPolicyFromUserGroupMsg msg) {
        SimpleQuery<UserGroupPolicyRefVO> q = dbf.createQuery(UserGroupPolicyRefVO.class);
        q.add(UserGroupPolicyRefVO_.groupUuid, Op.EQ, msg.getGroupUuid());
        q.add(UserGroupPolicyRefVO_.policyUuid, Op.EQ, msg.getPolicyUuid());
        UserGroupPolicyRefVO ref = q.find();
        if (ref != null) {
            dbf.remove(ref);
        }

        bus.publish(new APIDetachPolicyFromUserGroupEvent(msg.getId()));
    }

    private void handle(APIDetachPolicyFromUserMsg msg) {
        SimpleQuery<UserPolicyRefVO> q = dbf.createQuery(UserPolicyRefVO.class);
        q.add(UserPolicyRefVO_.policyUuid, Op.EQ, msg.getPolicyUuid());
        q.add(UserPolicyRefVO_.userUuid, Op.EQ, msg.getUserUuid());
        UserPolicyRefVO ref = q.find();
        if (ref != null) {
            dbf.remove(ref);
        }

        bus.publish(new APIDetachPolicyFromUserEvent(msg.getId()));
    }

    private void handle(APIDeletePolicyMsg msg) {
        dbf.removeByPrimaryKey(msg.getUuid(), PolicyVO.class);
        APIDeletePolicyEvent evt = new APIDeletePolicyEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIDeleteUserMsg msg) {
        dbf.removeByPrimaryKey(msg.getUuid(), UserVO.class);
        APIDeleteUserEvent evt = new APIDeleteUserEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIDeleteUserGroupMsg msg) {
        dbf.removeByPrimaryKey(msg.getUuid(), UserGroupVO.class);
        APIDeleteUserGroupEvent evt = new APIDeleteUserGroupEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIAddUserToGroupMsg msg) {
        UserGroupUserRefVO ugvo = new UserGroupUserRefVO();
        ugvo.setGroupUuid(msg.getGroupUuid());
        ugvo.setUserUuid(msg.getUserUuid());
        dbf.persist(ugvo);
        APIAddUserToGroupEvent evt = new APIAddUserToGroupEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APIAttachPolicyToUserGroupMsg msg) {
        UserGroupPolicyRefVO grvo = new UserGroupPolicyRefVO();
        grvo.setGroupUuid(msg.getGroupUuid());
        grvo.setPolicyUuid(msg.getPolicyUuid());
        dbf.persist(grvo);
        APIAttachPolicyToUserGroupEvent evt = new APIAttachPolicyToUserGroupEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APICreateUserGroupMsg msg) {
        UserGroupVO gvo = new UserGroupVO();
        if (msg.getResourceUuid() != null) {
            gvo.setUuid(msg.getResourceUuid());
        } else {
            gvo.setUuid(Platform.getUuid());
        }
        gvo.setAccountUuid(vo.getUuid());
        gvo.setDescription(msg.getDescription());
        gvo.setName(msg.getName());
        dbf.persistAndRefresh(gvo);

        acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), gvo.getUuid(), UserGroupVO.class);

        UserGroupInventory inv = UserGroupInventory.valueOf(gvo);
        APICreateUserGroupEvent evt = new APICreateUserGroupEvent(msg.getId());
        evt.setInventory(inv);
        bus.publish(evt);
    }

    private void handle(APIAttachPolicyToUserMsg msg) {
        UserPolicyRefVO upvo = new UserPolicyRefVO();
        upvo.setPolicyUuid(msg.getPolicyUuid());
        upvo.setUserUuid(msg.getUserUuid());
        dbf.persist(upvo);
        
        APIAttachPolicyToUserEvent evt = new APIAttachPolicyToUserEvent(msg.getId());
        bus.publish(evt);
    }

    private void handle(APICreatePolicyMsg msg) {
        PolicyVO pvo = new PolicyVO();
        if (msg.getResourceUuid() != null) {
            pvo.setUuid(msg.getResourceUuid());
        } else {
            pvo.setUuid(Platform.getUuid());
        }
        pvo.setAccountUuid(vo.getUuid());
        pvo.setName(msg.getName());
        pvo.setData(JSONObjectUtil.toJsonString(msg.getStatements()));
        pvo = dbf.persistAndRefresh(pvo);

        acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), pvo.getUuid(), PolicyVO.class);

        PolicyInventory pinv = PolicyInventory.valueOf(pvo);
        APICreatePolicyEvent evt = new APICreatePolicyEvent(msg.getId());
        evt.setInventory(pinv);
        bus.publish(evt);
    }



    private void handle(APICreateUserMsg msg) {
        APICreateUserEvent evt = new APICreateUserEvent(msg.getId());
        UserVO uvo = new UserVO();
        if (msg.getResourceUuid() != null) {
            uvo.setUuid(msg.getResourceUuid());
        } else {
            uvo.setUuid(Platform.getUuid());
        }
        uvo.setAccountUuid(vo.getUuid());
        uvo.setName(msg.getName());
        uvo.setPassword(msg.getPassword());
        uvo.setDescription(msg.getDescription());
        uvo = dbf.persistAndRefresh(uvo);

        SimpleQuery<PolicyVO> q = dbf.createQuery(PolicyVO.class);
        q.add(PolicyVO_.name, Op.EQ, String.format("DEFAULT-READ-%s", vo.getUuid()));
        PolicyVO p = q.find();
        if (p != null) {
            UserPolicyRefVO uref = new UserPolicyRefVO();
            uref.setPolicyUuid(p.getUuid());
            uref.setUserUuid(uvo.getUuid());
            dbf.persist(uref);
        }

        q = dbf.createQuery(PolicyVO.class);
        q.add(PolicyVO_.name, Op.EQ, String.format("USER-RESET-PASSWORD-%s", vo.getUuid()));
        p = q.find();
        if (p != null) {
            UserPolicyRefVO uref = new UserPolicyRefVO();
            uref.setPolicyUuid(p.getUuid());
            uref.setUserUuid(uvo.getUuid());
            dbf.persist(uref);
        }

        acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), uvo.getUuid(), UserVO.class);

        UserInventory inv = UserInventory.valueOf(uvo);
        evt.setInventory(inv);
        bus.publish(evt);
    }
}
