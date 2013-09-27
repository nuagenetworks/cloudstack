// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.acl;

import java.util.List;

import javax.ejb.Local;
import javax.inject.Inject;

import org.apache.log4j.Logger;

import org.apache.cloudstack.acl.dao.AclApiPermissionDao;
import org.apache.cloudstack.acl.dao.AclEntityPermissionDao;
import org.apache.cloudstack.acl.dao.AclGroupAccountMapDao;
import org.apache.cloudstack.acl.dao.AclGroupDao;
import org.apache.cloudstack.acl.dao.AclGroupRoleMapDao;
import org.apache.cloudstack.acl.dao.AclRoleDao;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Manager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;

@Local(value = {AclService.class})
public class AclServiceImpl extends ManagerBase implements AclService, Manager {

    public static final Logger s_logger = Logger.getLogger(AclServiceImpl.class);
    private String _name;

    @Inject
    AccountManager _accountMgr;

    @Inject
    AclRoleDao _aclRoleDao;

    @Inject
    AclGroupDao _aclGroupDao;

    @Inject
    AclGroupRoleMapDao _aclGroupRoleMapDao;

    @Inject
    AclGroupAccountMapDao _aclGroupAccountMapDao;

    @Inject
    AclApiPermissionDao _apiPermissionDao;

    @Inject
    AclEntityPermissionDao _entityPermissionDao;


    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_CREATE, eventDescription = "Creating Acl Role", create = true)
    public AclRole createAclRole(Long domainId, String aclRoleName, String description, Long parentRoleId) {
        Account caller = CallContext.current().getCallingAccount();
        if (!_accountMgr.isRootAdmin(caller.getAccountId())) {
            // domain admin can only create role for his domain
            if (domainId != null && caller.getDomainId() != domainId.longValue()) {
                throw new PermissionDeniedException("Can't create acl role in domain " + domainId + ", permission denied");
            }
        }
        // check if the role is already existing
        AclRole ro = _aclRoleDao.findByName(domainId, aclRoleName);
        if (ro != null) {
            throw new InvalidParameterValueException(
                    "Unable to create acl role with name " + aclRoleName
                            + " already exisits for domain " + domainId);
        }
        AclRoleVO rvo = new AclRoleVO(aclRoleName, description);
        if (domainId != null) {
            rvo.setDomainId(domainId);
        }
        if (parentRoleId != null) {
            rvo.setParentRoleId(parentRoleId);
        }
        return _aclRoleDao.persist(rvo);
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_DELETE, eventDescription = "Deleting Acl Role")
    public boolean deleteAclRole(long aclRoleId) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to delete acl role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // remove this role related entry in acl_group_role_map
        List<AclGroupRoleMapVO> groupRoleMap = _aclGroupRoleMapDao.listByRoleId(role.getId());
        if (groupRoleMap != null) {
            for (AclGroupRoleMapVO gr : groupRoleMap) {
                _aclGroupRoleMapDao.remove(gr.getId());
            }
        }

        // remove this role related entry in acl_api_permission table
        List<AclApiPermissionVO> roleApiMap = _apiPermissionDao.listByRoleId(role.getId());
        if (roleApiMap != null) {
            for (AclApiPermissionVO roleApi : roleApiMap) {
                _apiPermissionDao.remove(roleApi.getId());
            }
        }

        // remove this role from acl_role table
        _aclRoleDao.remove(aclRoleId);
        txn.commit();

        return true;
    }


    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_GRANT, eventDescription = "Granting permission to Acl Role")
    public AclRole grantPermissionToAclRole(long aclRoleId, List<String> apiNames) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to grant permission to role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // add entries in acl_api_permission table
        for (String api : apiNames) {
            AclApiPermissionVO perm = _apiPermissionDao.findByRoleAndApi(aclRoleId, api);
            if (perm == null) {
                // not there already
                perm = new AclApiPermissionVO(aclRoleId, api);
                _apiPermissionDao.persist(perm);
            }
        }
        txn.commit();
        return role;

    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_ROLE_REVOKE, eventDescription = "Revoking permission from Acl Role")
    public AclRole revokePermissionFromAclRole(long aclRoleId, List<String> apiNames) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Role entity
        AclRole role = _aclRoleDao.findById(aclRoleId);
        if (role == null) {
            throw new InvalidParameterValueException("Unable to find acl role: " + aclRoleId
                    + "; failed to revoke permission from role.");
        }
        // check permissions
        _accountMgr.checkAccess(caller, null, true, role);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // add entries in acl_api_permission table
        for (String api : apiNames) {
            AclApiPermissionVO perm = _apiPermissionDao.findByRoleAndApi(aclRoleId, api);
            if (perm != null) {
                // not removed yet
                _apiPermissionDao.remove(perm.getId());
            }
        }
        txn.commit();
        return role;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Adding roles to acl group")
    public AclGroup addAclRolesToGroup(List<Long> roleIds, Long groupId) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to add roles to acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);
 
        Transaction txn = Transaction.currentTxn();
        txn.start();
        // add entries in acl_group_role_map table
        for (Long roleId : roleIds) {
            // check role permissions
            AclRole role = _aclRoleDao.findById(roleId);
            if ( role == null ){
                throw new InvalidParameterValueException("Unable to find acl role: " + roleId
                        + "; failed to add roles to acl group.");
            }
            _accountMgr.checkAccess(caller,null, true, role);
            
            AclGroupRoleMapVO grMap = _aclGroupRoleMapDao.findByGroupAndRole(groupId, roleId);
            if (grMap == null) {
                // not there already
                grMap = new AclGroupRoleMapVO(groupId, roleId);
                _aclGroupRoleMapDao.persist(grMap);
            }
        }
        txn.commit();
        return group;
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ACL_GROUP_UPDATE, eventDescription = "Removing roles from acl group")
    public AclGroup removeAclRolesFromGroup(List<Long> roleIds, Long groupId) {
        Account caller = CallContext.current().getCallingAccount();
        // get the Acl Group entity
        AclGroup group = _aclGroupDao.findById(groupId);
        if (group == null) {
            throw new InvalidParameterValueException("Unable to find acl group: " + groupId
                    + "; failed to remove roles from acl group.");
        }
        // check group permissions
        _accountMgr.checkAccess(caller, null, true, group);

        Transaction txn = Transaction.currentTxn();
        txn.start();
        // add entries in acl_group_role_map table
        for (Long roleId : roleIds) {
            // check role permissions
            AclRole role = _aclRoleDao.findById(roleId);
            if (role == null) {
                throw new InvalidParameterValueException("Unable to find acl role: " + roleId
                        + "; failed to add roles to acl group.");
            }
            _accountMgr.checkAccess(caller, null, true, role);

            AclGroupRoleMapVO grMap = _aclGroupRoleMapDao.findByGroupAndRole(groupId, roleId);
            if (grMap != null) {
                // not removed yet
                _aclGroupRoleMapDao.remove(grMap.getId());
            }
        }
        txn.commit();
        return group;
    }

    @Override
    public AclGroup createAclGroup(Long domainId, String aclGroupName, String description) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean deleteAclGroup(Long aclGroupId) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Pair<List<? extends AclRole>, Integer> listAclGroups(Long aclRoleId, String aclRoleName, Long domainId, Long startIndex, Long pageSize) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public AclRole getAclGroup(Long groupId) {
        // TODO Auto-generated method stub
        return null;
    }


}
