package org.zstack.storage.volume;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.cascade.*;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.header.configuration.DiskOffering;
import org.zstack.header.configuration.DiskOfferingInventory;
import org.zstack.header.configuration.DiskOfferingVO;
import org.zstack.header.core.Completion;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.identity.AccountInventory;
import org.zstack.header.identity.AccountVO;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.l3.L3NetworkInventory;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.storage.primary.PrimaryStorageInventory;
import org.zstack.header.storage.primary.PrimaryStorageVO;
import org.zstack.header.volume.*;
import org.zstack.identity.Account;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import javax.persistence.Query;
import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 */
public class VolumeCascadeExtension extends AbstractAsyncCascadeExtension {
    private static final CLogger logger = Utils.getLogger(VolumeCascadeExtension.class);
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private CloudBus bus;

    private static final String NAME = VolumeVO.class.getSimpleName();

    private static final int OP_DELETE_VOLUME = 0;
    private static final int OP_UPDATE_DISK_OFFERING_COLUMN = 1;

    @Override
    public void asyncCascade(CascadeAction action, Completion completion) {
        if (action.isActionCode(CascadeConstant.DELETION_CHECK_CODE)) {
            handleDeletionCheck(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_DELETE_CODE, CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
            handleDeletion(action, completion);
        } else if (action.isActionCode(CascadeConstant.DELETION_CLEANUP_CODE)) {
            handleDeletionCleanup(action, completion);
        } else {
            completion.success();
        }
    }

    private int actionToOpCode(CascadeAction action) {
        if (action.getParentIssuer().equals(NAME) || action.getParentIssuer().equals(PrimaryStorageVO.class.getSimpleName())) {
            return OP_DELETE_VOLUME;
        }

        if (action.getParentIssuer().equals(DiskOfferingVO.class.getSimpleName())) {
            return OP_UPDATE_DISK_OFFERING_COLUMN;
        }

        if (action.getParentIssuer().equals(AccountVO.class.getSimpleName())) {
            return OP_DELETE_VOLUME;
        }

        throw new CloudRuntimeException(String.format("unknown edge [%s]", action.getParentIssuer()));
    }

    private void handleDeletionCleanup(CascadeAction action, Completion completion) {
        dbf.eoCleanup(VolumeVO.class);
        completion.success();
    }

    private List<VolumeInventory> volumesFromAction(CascadeAction action) {
        if (CascadeConstant.DELETION_CODES.contains(action.getActionCode())) {
            if (NAME.equals(action.getParentIssuer())) {
                return action.getParentIssuerContext();
            } else if (PrimaryStorageVO.class.getSimpleName().equals(action.getParentIssuer())) {
                List<PrimaryStorageInventory> pinvs = action.getParentIssuerContext();
                List<String> psUuids = CollectionUtils.transformToList(pinvs, new Function<String, PrimaryStorageInventory>() {
                    @Override
                    public String call(PrimaryStorageInventory arg) {
                        return arg.getUuid();
                    }
                });

                SimpleQuery<VolumeVO> q = dbf.createQuery(VolumeVO.class);
                q.add(VolumeVO_.type, Op.EQ, VolumeType.Data);
                q.add(VolumeVO_.primaryStorageUuid, Op.IN, psUuids);
                List<VolumeVO> vos = q.list();
                return VolumeInventory.valueOf(vos);
            } else if (AccountVO.class.getSimpleName().equals(action.getParentIssuer())) {
                final List<String> auuids = CollectionUtils.transformToList((List<AccountInventory>) action.getParentIssuerContext(), new Function<String, AccountInventory>() {
                    @Override
                    public String call(AccountInventory arg) {
                        return arg.getUuid();
                    }
                });

                List<VolumeVO> vos = new Callable<List<VolumeVO>>() {
                    @Override
                    @Transactional(readOnly = true)
                    public List<VolumeVO> call() {
                        String sql = "select d from VolumeVO d, AccountResourceRefVO r where d.uuid = r.resourceUuid and" +
                                " r.resourceType = :rtype and r.accountUuid in (:auuids) and d.type = :dtype";
                        TypedQuery<VolumeVO> q = dbf.getEntityManager().createQuery(sql, VolumeVO.class);
                        q.setParameter("auuids", auuids);
                        q.setParameter("rtype", VolumeVO.class.getSimpleName());
                        q.setParameter("dtype", VolumeType.Data);
                        return q.getResultList();
                    }
                }.call();

                if (!vos.isEmpty()) {
                    return VolumeInventory.valueOf(vos);
                }
            }
        }

        return null;
    }

    private void handleDeletion(final CascadeAction action, final Completion completion) {
        int op = actionToOpCode(action);
        
        if (op == OP_DELETE_VOLUME) {
            deleteVolume(action, completion);
        } else if (op == OP_UPDATE_DISK_OFFERING_COLUMN) {
            if (VolumeGlobalConfig.UPDATE_DISK_OFFERING_TO_NULL_WHEN_DELETING.value(Boolean.class)) {
                updateDiskOfferingColumn(action, completion);
            } else {
                completion.success();
            }
        }
    }

    @Transactional
    private void updateDiskOfferingColumn(CascadeAction action, Completion completion) {
        List<DiskOfferingInventory> diskOfferingInventories = action.getParentIssuerContext();
        List<String> diskOfferingUuids = CollectionUtils.transformToList(diskOfferingInventories, new Function<String, DiskOfferingInventory>() {
            @Override
            public String call(DiskOfferingInventory arg) {
                return arg.getUuid();
            }
        });

        String sql = "update VolumeVO vol set vol.diskOfferingUuid = null where vol.diskOfferingUuid in (:uuids)";
        Query q = dbf.getEntityManager().createQuery(sql);
        q.setParameter("uuids", diskOfferingUuids);
        q.executeUpdate();
        completion.success();
    }

    private void deleteVolume(final CascadeAction action, final Completion completion) {
        final List<VolumeInventory> volumes = volumesFromAction(action);
        if (volumes == null || volumes.isEmpty()) {
            completion.success();
            return;
        }

        List<VolumeDeletionMsg> msgs = new ArrayList<VolumeDeletionMsg>();
        for (VolumeInventory vol : volumes) {
            VolumeDeletionMsg msg = new VolumeDeletionMsg();
            msg.setDetachBeforeDeleting(!(vol instanceof VolumeDeletionStruct) || ((VolumeDeletionStruct) vol).isDetachBeforeDeleting());
            msg.setForceDelete(action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE));
            msg.setVolumeUuid(vol.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, VolumeConstant.SERVICE_ID, vol.getUuid());
            msgs.add(msg);
        }

        bus.send(msgs, 20, new CloudBusListCallBack(completion) {
            @Override
            public void run(List<MessageReply> replies) {
                if (!action.isActionCode(CascadeConstant.DELETION_FORCE_DELETE_CODE)) {
                    for (MessageReply r : replies) {
                        if (!r.isSuccess()) {
                            completion.fail(r.getError());
                            return;
                        }
                    }
                }

                completion.success();
            }
        }); 
    }

    private void handleDeletionCheck(CascadeAction action, Completion completion) {
        completion.success();
    }

    @Override
    public List<String> getEdgeNames() {
        return Arrays.asList(PrimaryStorageVO.class.getSimpleName(),
                DiskOfferingVO.class.getSimpleName(), AccountVO.class.getSimpleName());
    }

    @Override
    public String getCascadeResourceName() {
        return NAME;
    }

    @Override
    public CascadeAction createActionForChildResource(CascadeAction action) {
        int op = actionToOpCode(action);

        if (op == OP_DELETE_VOLUME) {
            List<VolumeInventory> invs = volumesFromAction(action);
            if (invs != null) {
                return action.copy().setParentIssuer(NAME).setParentIssuerContext(invs);
            }
        }

        return null;
    }
}
